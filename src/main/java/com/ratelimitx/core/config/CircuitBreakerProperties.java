package com.ratelimitx.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Circuit breaker tuning.
 *
 * <p>These were compile-time constants, so changing either one meant a rebuild.
 */
@Component
@ConfigurationProperties(prefix = "ratelimit.circuit-breaker")
public class CircuitBreakerProperties {

    /** Consecutive Redis failures that open the circuit. */
    private int failureThreshold = 3;

    /** How long the circuit stays open before a probe is allowed through. */
    private Duration timeout = Duration.ofSeconds(30);

    @PostConstruct
    void validate() {
        if (failureThreshold < 1) {
            throw new IllegalStateException(
                    "ratelimit.circuit-breaker.failure-threshold must be at least 1, got "
                            + failureThreshold);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException(
                    "ratelimit.circuit-breaker.timeout must be a positive duration, got " + timeout);
        }
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
