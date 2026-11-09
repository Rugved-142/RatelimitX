package com.ratelimitx.core.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ratelimitx.core.config.CircuitBreakerProperties;
import com.ratelimitx.core.service.RateLimitScriptException;

class CircuitBreakerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private MutableClock clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingNow();
        CircuitBreakerProperties properties = new CircuitBreakerProperties();
        properties.setFailureThreshold(3);
        properties.setTimeout(TIMEOUT);
        breaker = new CircuitBreaker(properties, clock);
    }

    private static RuntimeException redisDown() {
        return new RedisConnectionFailureException("connection refused");
    }

    private String callFailing() {
        return breaker.execute(() -> {
            throw redisDown();
        }, () -> "fallback");
    }

    // ==================== state transitions ====================

    @Test
    void startsClosedAndPassesResultsThrough() {
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals("primary", breaker.execute(() -> "primary", () -> "fallback"));
    }

    @Test
    void opensExactlyAtTheFailureThreshold() {
        assertEquals("fallback", callFailing());
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState(), "1 failure of 3");

        assertEquals("fallback", callFailing());
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState(), "2 failures of 3");

        assertEquals("fallback", callFailing());
        assertEquals(CircuitBreakerState.OPEN, breaker.getState(), "3rd failure opens it");
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        callFailing();
        callFailing();
        breaker.execute(() -> "ok", () -> "fallback");

        callFailing();
        callFailing();
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState(),
                "the run was broken, so two more failures must not open it");
    }

    @Test
    void whileOpenTheOperationIsNotCalledAtAll() {
        openTheCircuit();

        AtomicInteger calls = new AtomicInteger();
        String result = breaker.execute(() -> {
            calls.incrementAndGet();
            return "primary";
        }, () -> "fallback");

        assertEquals("fallback", result);
        assertEquals(0, calls.get(), "an open circuit must not touch Redis");
    }

    @Test
    void movesToHalfOpenOnlyAfterTheTimeout() {
        openTheCircuit();

        clock.advance(TIMEOUT.minusMillis(1));
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        clock.advanceMillis(1);
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
    }

    @Test
    void aSuccessfulProbeClosesTheCircuit() {
        openTheCircuit();
        clock.advance(TIMEOUT);

        assertEquals("primary", breaker.execute(() -> "primary", () -> "fallback"));
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(0, breaker.getStatus().get("failureCount"));
    }

    @Test
    void aFailedProbeReopensTheCircuitForAnotherTimeout() {
        openTheCircuit();
        clock.advance(TIMEOUT);

        assertEquals("fallback", callFailing());
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        clock.advance(TIMEOUT.minusMillis(1));
        assertEquals(CircuitBreakerState.OPEN, breaker.getState(),
                "the timeout restarts from the failed probe");

        clock.advanceMillis(1);
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
    }

    @Test
    void resetReturnsItToClosed() {
        openTheCircuit();
        breaker.reset();

        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(0, breaker.getStatus().get("failureCount"));
        assertTrue(breaker.isAllowingRequests());
    }

    // ==================== one probe at a time ====================

    @Test
    void halfOpenLetsExactlyOneProbeThroughWhileOthersUseTheFallback() throws Exception {
        openTheCircuit();
        clock.advance(TIMEOUT);

        int threads = 32;
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger fallbacks = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                go.await();
                breaker.execute(() -> {
                    // Hold the probe open so every other thread arrives while it is in flight.
                    probes.incrementAndGet();
                    probeStarted.countDown();
                    try {
                        releaseProbe.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "primary";
                }, () -> {
                    fallbacks.incrementAndGet();
                    return "fallback";
                });
                allDone.countDown();
                return null;
            });
        }

        go.countDown();
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS), "no probe started");
        // Give the other threads time to arrive and be turned away.
        Thread.sleep(200);
        releaseProbe.countDown();

        assertTrue(allDone.await(10, TimeUnit.SECONDS), "threads did not finish");
        pool.shutdownNow();

        assertEquals(1, probes.get(), "exactly one request may probe Redis in HALF_OPEN");
        assertEquals(threads - 1, fallbacks.get(), "every other request must use the fallback");
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
    }

    @Test
    void theProbeSlotIsReleasedAfterAFailedProbe() {
        openTheCircuit();
        clock.advance(TIMEOUT);

        callFailing();
        assertFalse((Boolean) breaker.getStatus().get("probeInFlight"),
                "a failed probe must not leave the slot held, or recovery stalls forever");

        clock.advance(TIMEOUT);
        assertEquals("primary", breaker.execute(() -> "primary", () -> "fallback"));
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
    }

    // ==================== failure classification ====================

    @Test
    void countsRedisConnectivityAndTimeoutFailures() {
        for (RuntimeException failure : java.util.List.of(
                new RedisConnectionFailureException("refused"),
                new QueryTimeoutException("command timed out"),
                new RateLimitScriptException("sliding-window", "{sliding:bob}"))) {
            CircuitBreakerProperties properties = new CircuitBreakerProperties();
            properties.setFailureThreshold(1);
            properties.setTimeout(TIMEOUT);
            CircuitBreaker fresh = new CircuitBreaker(properties, MutableClock.startingNow());

            assertEquals("fallback", fresh.execute(() -> {
                throw failure;
            }, () -> "fallback"));
            assertEquals(CircuitBreakerState.OPEN, fresh.getState(),
                    failure.getClass().getSimpleName() + " should count as a Redis failure");
        }
    }

    @Test
    void countsAWrappedRedisFailure() {
        RuntimeException wrapped =
                new IllegalStateException("script failed", new QueryTimeoutException("timeout"));

        assertEquals("fallback", breaker.execute(() -> {
            throw wrapped;
        }, () -> "fallback"));
        assertEquals(1, breaker.getStatus().get("failureCount"));
    }

    @Test
    void doesNotCountOrHideABug() {
        NullPointerException bug = new NullPointerException("limit was null");

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> breaker.execute(() -> {
                    throw bug;
                }, () -> "fallback"));

        assertSame(bug, thrown, "a bug must reach the caller, not be swallowed by the fallback");
        assertEquals(0, breaker.getStatus().get("failureCount"),
                "a bug must not count towards opening the circuit");
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
    }

    @Test
    void manyBugsNeverOpenTheCircuit() {
        for (int i = 0; i < 50; i++) {
            assertThrows(IllegalArgumentException.class, () -> breaker.execute(() -> {
                throw new IllegalArgumentException("bad input");
            }, () -> "fallback"));
        }
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
    }

    // ==================== status ====================

    @Test
    void recordsTheTimeOfTheLastFailure() {
        assertFalse(breaker.getStatus().containsKey("lastFailureTime"),
                "nothing has failed yet");

        callFailing();

        assertEquals(clock.instant().toString(), breaker.getStatus().get("lastFailureTime"),
                "lastFailureTime was previously declared but never assigned");
    }

    @Test
    void reportsTheConfiguredThresholdAndTimeout() {
        CircuitBreakerProperties properties = new CircuitBreakerProperties();
        properties.setFailureThreshold(7);
        properties.setTimeout(Duration.ofSeconds(5));
        CircuitBreaker configured = new CircuitBreaker(properties, clock);

        assertEquals(7, configured.getStatus().get("failureThreshold"));
        assertEquals(5000L, configured.getStatus().get("timeoutDurationMs"));
    }

    @Test
    void reportsTimeRemainingUntilTheNextProbe() {
        openTheCircuit();
        clock.advance(Duration.ofSeconds(10));

        assertEquals(TIMEOUT.toMillis() - 10_000, breaker.getTimeUntilHalfOpenMs());
    }

    @Test
    void stateCodesMatchTheDocumentedGaugeValues() {
        assertEquals(0, CircuitBreakerState.CLOSED.getCode());
        assertEquals(1, CircuitBreakerState.HALF_OPEN.getCode());
        assertEquals(2, CircuitBreakerState.OPEN.getCode());
    }

    private void openTheCircuit() {
        for (int i = 0; i < 3; i++) {
            callFailing();
        }
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());
    }
}
