package com.ratelimitx.core.circuitbreaker;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ratelimitx.core.config.CircuitBreakerProperties;

/**
 * Circuit breaker around the Redis-backed rate limiters.
 *
 * <p>CLOSED runs every request against Redis. After {@code failureThreshold} consecutive Redis
 * failures the circuit goes OPEN and requests use the fallback. Once the timeout has elapsed the
 * circuit goes HALF_OPEN and lets a <strong>single</strong> probe through; if it succeeds the
 * circuit closes, and if it fails the circuit opens again for another timeout.
 *
 * <p>Two properties of this implementation are worth stating because they are easy to get wrong:
 *
 * <ul>
 *   <li><strong>Only one probe runs at a time.</strong> Every other request arriving while a probe
 *       is in flight takes the fallback. Without that, re-opening the gate under load sends the
 *       entire arriving load at a Redis that has just been failing.
 *   <li><strong>Only Redis connectivity and timeout failures count.</strong> Anything else
 *       propagates to the caller instead of being counted and hidden behind the fallback. See
 *       {@link RedisFailureClassifier}.
 * </ul>
 *
 * <p>All times come from an injected {@link Clock} so the state machine can be tested without
 * sleeping.
 */
@Component
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    private final CircuitBreakerProperties properties;
    private final Clock clock;

    private final AtomicReference<CircuitBreakerState> state =
            new AtomicReference<>(CircuitBreakerState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    /** True while a HALF_OPEN probe is in flight, so only one request tests Redis at a time. */
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    public CircuitBreaker(CircuitBreakerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs {@code operation}, falling back to {@code fallback} when the circuit is open or the
     * operation fails because of Redis.
     *
     * @throws RuntimeException if the operation fails for any reason unrelated to Redis; such a
     *         failure is a bug and is not hidden behind the fallback
     */
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        return switch (currentState()) {
            case CLOSED -> executeClosed(operation, fallback);
            case OPEN -> executeOpen(operation, fallback);
            case HALF_OPEN -> executeHalfOpen(operation, fallback);
        };
    }

    /**
     * Reads the state, first promoting OPEN to HALF_OPEN if the timeout has elapsed. Doing the
     * promotion here keeps {@link #getState()} and the gauge honest, instead of leaving the
     * circuit reported as OPEN until the next request happens to arrive.
     */
    private CircuitBreakerState currentState() {
        CircuitBreakerState current = state.get();
        if (current == CircuitBreakerState.OPEN && timeoutElapsed()) {
            if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                logger.info("Circuit breaker: OPEN -> HALF_OPEN (timeout elapsed, probing Redis)");
                return CircuitBreakerState.HALF_OPEN;
            }
            return state.get();
        }
        return current;
    }

    private boolean timeoutElapsed() {
        return clock.millis() - openedAt.get() >= properties.getTimeout().toMillis();
    }

    private <T> T executeClosed(Supplier<T> operation, Supplier<T> fallback) {
        try {
            T result = operation.get();
            failureCount.set(0);
            return result;
        } catch (RuntimeException e) {
            if (!RedisFailureClassifier.isRedisFailure(e)) {
                throw e;
            }
            int failures = recordFailure();
            logger.warn("Circuit breaker: Redis failure {}/{} in CLOSED: {}",
                    failures, properties.getFailureThreshold(), e.toString());
            return fallback.get();
        }
    }

    private <T> T executeOpen(Supplier<T> operation, Supplier<T> fallback) {
        logger.debug("Circuit breaker: OPEN, using fallback");
        return fallback.get();
    }

    private <T> T executeHalfOpen(Supplier<T> operation, Supplier<T> fallback) {
        // Exactly one request probes Redis; everyone else degrades rather than piling onto a
        // dependency that was failing moments ago.
        if (!probeInFlight.compareAndSet(false, true)) {
            logger.debug("Circuit breaker: HALF_OPEN, probe already in flight, using fallback");
            return fallback.get();
        }

        try {
            T result = operation.get();
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                failureCount.set(0);
                logger.info("Circuit breaker: HALF_OPEN -> CLOSED (probe succeeded)");
            }
            return result;
        } catch (RuntimeException e) {
            if (!RedisFailureClassifier.isRedisFailure(e)) {
                // Not Redis's fault: leave the circuit testing rather than opening it on a bug.
                throw e;
            }
            recordFailure();
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                openedAt.set(clock.millis());
                logger.warn("Circuit breaker: HALF_OPEN -> OPEN (probe failed: {})", e.toString());
            }
            return fallback.get();
        } finally {
            probeInFlight.set(false);
        }
    }

    private int recordFailure() {
        lastFailureTime.set(clock.millis());
        int failures = failureCount.incrementAndGet();

        if (failures >= properties.getFailureThreshold()
                && state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
            openedAt.set(clock.millis());
            logger.error("Circuit breaker: CLOSED -> OPEN after {} consecutive Redis failures",
                    failures);
        }
        return failures;
    }

    // ==================== STATUS ====================

    /** @return the current state, promoting OPEN to HALF_OPEN if the timeout has elapsed. */
    public CircuitBreakerState getState() {
        return currentState();
    }

    public long getTimeSinceOpenedMs() {
        if (state.get() == CircuitBreakerState.CLOSED) {
            return 0;
        }
        return clock.millis() - openedAt.get();
    }

    public long getTimeUntilHalfOpenMs() {
        if (state.get() != CircuitBreakerState.OPEN) {
            return 0;
        }
        long remaining = properties.getTimeout().toMillis() - (clock.millis() - openedAt.get());
        return Math.max(0, remaining);
    }

    /** @return false only while the circuit is OPEN, when every request uses the fallback. */
    public boolean isAllowingRequests() {
        return currentState() != CircuitBreakerState.OPEN;
    }

    public Map<String, Object> getStatus() {
        CircuitBreakerState current = currentState();

        Map<String, Object> status = new HashMap<>();
        status.put("state", current.toString());
        status.put("failureCount", failureCount.get());
        status.put("failureThreshold", properties.getFailureThreshold());
        status.put("timeoutDurationMs", properties.getTimeout().toMillis());
        status.put("isAllowingRequests", current != CircuitBreakerState.OPEN);
        status.put("probeInFlight", probeInFlight.get());

        if (current == CircuitBreakerState.OPEN) {
            status.put("timeInOpenStateMs", getTimeSinceOpenedMs());
            status.put("timeUntilRetryMs", getTimeUntilHalfOpenMs());
        }

        long failedAt = lastFailureTime.get();
        if (failedAt > 0) {
            status.put("lastFailureTime", Instant.ofEpochMilli(failedAt).toString());
        }

        return status;
    }

    public void reset() {
        state.set(CircuitBreakerState.CLOSED);
        failureCount.set(0);
        openedAt.set(0);
        lastFailureTime.set(0);
        probeInFlight.set(false);
        logger.info("Circuit breaker: manually reset to CLOSED");
    }
}
