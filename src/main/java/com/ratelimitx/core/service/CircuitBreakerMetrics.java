package com.ratelimitx.core.service;

import org.springframework.stereotype.Component;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the circuit breaker's state as {@code app.circuitbreaker.state}.
 *
 * <p>The gauge reads {@link CircuitBreaker#getState()} on every scrape. It previously mirrored a
 * field that a setter was supposed to keep up to date, but nothing ever called that setter, so the
 * gauge reported CLOSED for the lifetime of the process and the Grafana panel built on it was
 * always wrong. Binding to the breaker directly means there is no second copy of the state to
 * drift.
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
