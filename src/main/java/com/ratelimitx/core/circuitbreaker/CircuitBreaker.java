package com.ratelimitx.core.circuitbreaker;


import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;




@Component
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    private static final int FAILURE_THRESHOLD = 3;
    private static final int TIMEOUT_DURATION_MS = 30000;

    private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastFailureTime = 0;
    private volatile long openedAt = 0;

    public <T> T execute(Supplier<T> operation, Supplier<T> fallback){

        CircuitBreakerState currentState = state.get();

        switch(currentState){
            case CLOSED:
                return executeInClosedState(operation, fallback);
            case OPEN:
                return executeInOpenState(operation, fallback);
            case HALF_OPEN:
                return executeInHalfOpenState(operation, fallback);
            default:
                return fallback.get();
        }
    }

    private <T> T executeInClosedState(Supplier<T> operation, Supplier<T> fallback){


            try{
                T result= operation.get();
                onSuccess();
                return result;
            }catch(Exception e){
                onFailure();

                logger.warn("Circuit Breaker: Operation failed in CLOSED state. Failures: {}/{}",
                failureCount.get(), FAILURE_THRESHOLD);

                return fallback.get();
            }
        }
    private <T> T executeInOpenState(Supplier<T> operation, Supplier<T> fallback) {

        if (System.currentTimeMillis() - openedAt >= TIMEOUT_DURATION_MS) {
            if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                logger.info("Circuit Breaker: OPEN → HALF_OPEN (timeout passed, testing Redis)");
            }
            return executeInHalfOpenState(operation, fallback);
        }

        logger.debug("Circuit Breaker: OPEN state, using fallback");
        return fallback.get();
    }
    private <T> T executeInHalfOpenState(Supplier<T> operation, Supplier<T> fallback) {
        try {
            T result = operation.get();

            // Success! Redis is back - close the circuit
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                failureCount.set(0);
                logger.info("Circuit Breaker: HALF_OPEN → CLOSED (Redis recovered!)");
            }

            return result;

        } catch (Exception e) {
            // Still failing - go back to OPEN
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                openedAt = System.currentTimeMillis();
                logger.warn("Circuit Breaker: HALF_OPEN → OPEN (Redis still down)");
            }

            return fallback.get();
        }
    }

    private void onSuccess() {
        failureCount.set(0);
    }

    private void onFailure() {
        int failures = failureCount.incrementAndGet();

        if (failures >= FAILURE_THRESHOLD) {
            // Open the circuit!
            if (state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
                openedAt = System.currentTimeMillis();
                logger.error("Circuit Breaker: CLOSED → OPEN (threshold reached: {} failures)", failures);
            }
        }
    }


    // ==================== STATUS METHODS ====================

    public CircuitBreakerState getState() {
        return state.get();
    }

    public long getTimeSinceOpenedMs() {
        if (state.get() == CircuitBreakerState.CLOSED) {
            return 0;
        }
        return System.currentTimeMillis() - openedAt;
    }

    public long getTimeUntilHalfOpenMs() {
        if (state.get() != CircuitBreakerState.OPEN) {
            return 0;
        }
        long remaining = TIMEOUT_DURATION_MS - (System.currentTimeMillis() - openedAt);
        return Math.max(0, remaining);
    }

    public boolean isAllowingRequests() {
        return state.get() != CircuitBreakerState.OPEN;
    }

    public java.util.Map<String, Object> getStatus() {
        java.util.Map<String, Object> status = new java.util.HashMap<>();

        status.put("state", state.get().toString());
        status.put("failureCount", failureCount.get());
        status.put("failureThreshold", FAILURE_THRESHOLD);
        status.put("timeoutDurationMs", TIMEOUT_DURATION_MS);
        status.put("isAllowingRequests", isAllowingRequests());

        if (state.get() == CircuitBreakerState.OPEN) {
            status.put("timeInOpenStateMs", getTimeSinceOpenedMs());
            status.put("timeUntilRetryMs", getTimeUntilHalfOpenMs());
        }

        if (lastFailureTime > 0) {
            status.put("lastFailureTime", Instant.ofEpochMilli(lastFailureTime).toString());
        }

        return status;
    }

    public void reset() {
        state.set(CircuitBreakerState.CLOSED);
        failureCount.set(0);
        openedAt = 0;
        lastFailureTime = 0;
        logger.info("Circuit Breaker: Manually reset to CLOSED");
    }
}
