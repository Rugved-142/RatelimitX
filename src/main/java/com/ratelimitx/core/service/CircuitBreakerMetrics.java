package com.ratelimitx.core.service;

import org.springframework.stereotype.Component;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the circuit breaker's state as {@code app.circuitbreaker.state}.
 *
 * <p>The gauge is bound to the breaker itself and calls {@link CircuitBreaker#getState()} on
 * every scrape, so there is no cached copy of the state that could drift from the real one.
 *
 * <p>Values are {@code CircuitBreakerState.getCode()}: 0 CLOSED, 1 HALF_OPEN, 2 OPEN.
 */
@Component
public class CircuitBreakerMetrics {

    public CircuitBreakerMetrics(MeterRegistry registry, CircuitBreaker circuitBreaker) {
        Gauge.builder("app.circuitbreaker.state", circuitBreaker,
                        breaker -> breaker.getState().getCode())
                .description("Circuit breaker state: 0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                .strongReference(true)
                .register(registry);
    }
}
