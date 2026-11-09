package com.ratelimitx.core.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ratelimitx.core.model.RateLimitResult;

class LocalRateLimiterTest {

    private static final long WINDOW_MS = 60_000L;

    private MutableClock clock;
    private LocalRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingNow();
        limiter = new LocalRateLimiter(clock);
        ReflectionTestUtils.setField(limiter, "windowSizeMs", WINDOW_MS);
        ReflectionTestUtils.setField(limiter, "defaultMaxRequests", 10);
        ReflectionTestUtils.setField(limiter, "evictionAfterMs", 300_000L);
    }

    @Test
    void admitsExactlyTheLimitThenDenies() {
        for (int i = 1; i <= 5; i++) {
            RateLimitResult result = limiter.isAllowed("alice", 5);
            assertTrue(result.isAllowed(), "request " + i + " should be allowed");
            assertEquals(5 - i, result.getRemaining());
        }

        RateLimitResult denied = limiter.isAllowed("alice", 5);
        assertFalse(denied.isAllowed());
        assertEquals(0, denied.getRemaining());
    }

    @Test
    void countsEachUserSeparately() {
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.isAllowed("alice", 5).isAllowed());
        }
        assertFalse(limiter.isAllowed("alice", 5).isAllowed());
        assertTrue(limiter.isAllowed("bob", 5).isAllowed(), "bob has his own window");
    }

    @Test
    void admitsAgainAfterTheWindowRollsOver() {
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed("alice", 5);
        }
        assertFalse(limiter.isAllowed("alice", 5).isAllowed());

        clock.advanceMillis(WINDOW_MS);

        RateLimitResult afterRollover = limiter.isAllowed("alice", 5);
        assertTrue(afterRollover.isAllowed());
        assertEquals(4, afterRollover.getRemaining());
    }

    @Test
    void reportsMillisecondsUntilTheWindowResets() {
        RateLimitResult first = limiter.isAllowed("alice", 5);
        assertTrue(first.getResetTime() > 0 && first.getResetTime() <= WINDOW_MS);

        clock.advanceMillis(20_000);
        long remaining = limiter.isAllowed("alice", 5).getResetTime();
        assertEquals(WINDOW_MS - 20_000, remaining);
    }

    @Test
    void retryAfterIsNeverBelowOneSecond() {
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed("alice", 5);
        }
        // One millisecond before the boundary, so resetTime rounds down to zero seconds.
        clock.advanceMillis(WINDOW_MS - 1);
        RateLimitResult denied = limiter.isAllowed("alice", 5);
        assertFalse(denied.isAllowed());
        assertTrue(denied.retryAfterSeconds() >= 1);
    }

    /**
     * The previous implementation read the count and then incremented it as two steps, so threads
     * could interleave between the two and admit more than the limit. Repeated because a race
     * does not reproduce on every run.
     */
    @RepeatedTest(5)
    void neverAdmitsMoreThanTheLimitUnderConcurrency() throws Exception {
        int threads = 200;
        int limit = 50;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        Callable<Void> task = () -> {
            go.await();
            if (limiter.isAllowed("hot-key", limit).isAllowed()) {
                allowed.incrementAndGet();
            }
            return null;
        };

        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(task);
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(limit, allowed.get(),
                "concurrent requests must not admit more than the limit");
    }

    /** A rollover racing with increments must not lose or duplicate admissions. */
    @RepeatedTest(5)
    void rolloverUnderConcurrencyKeepsTheLimitExact() throws Exception {
        int limit = 20;
        for (int i = 0; i < limit; i++) {
            limiter.isAllowed("hot-key", limit);
        }
        assertFalse(limiter.isAllowed("hot-key", limit).isAllowed());

        clock.advanceMillis(WINDOW_MS);

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                go.await();
                if (limiter.isAllowed("hot-key", limit).isAllowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(limit, allowed.get(),
                "exactly the limit should pass in the new window, no more and no fewer");
    }

    // ==================== eviction ====================

    @Test
    void evictsCountersThatHaveBeenIdleLongerThanTheThreshold() {
        limiter.isAllowed("alice", 5);
        limiter.isAllowed("bob", 5);
        assertEquals(2, limiter.getActiveUsers());

        clock.advance(Duration.ofMinutes(10));
        limiter.evictStaleCounters();

        assertEquals(0, limiter.getActiveUsers(), "counters idle for 10 minutes should be gone");
    }

    @Test
    void keepsCountersThatAreStillInUse() {
        limiter.isAllowed("alice", 5);

        clock.advance(Duration.ofMinutes(10));
        limiter.isAllowed("alice", 5);
        limiter.evictStaleCounters();

        assertEquals(1, limiter.getActiveUsers(), "a counter touched just now must survive");
    }

    @Test
    void evictionDoesNotResetAnActiveWindow() {
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed("alice", 5);
        }
        limiter.evictStaleCounters();

        assertFalse(limiter.isAllowed("alice", 5).isAllowed(),
                "eviction must not hand an exhausted user a fresh window");
    }

    @Test
    void clearRemovesEverything() {
        limiter.isAllowed("alice", 5);
        limiter.isAllowed("bob", 5);
        limiter.clear();
        assertEquals(0, limiter.getActiveUsers());
    }
}
