package com.ratelimitx.core.circuitbreaker;

/**
 * Circuit breaker states.
 *
 * <p>Each carries an explicit numeric code, which is what the {@code app.circuitbreaker.state}
 * gauge publishes. The codes are deliberately not {@link Enum#ordinal()}: reordering or inserting
 * a constant would silently renumber the gauge and change the meaning of every historical sample
 * in Prometheus.
 */
public enum CircuitBreakerState {

    /** Normal operation; requests go to Redis. */
    CLOSED(0),

    /** Testing recovery; exactly one probe is allowed through at a time. */
    HALF_OPEN(1),

    /** Redis is considered down; requests use the local fallback. */
    OPEN(2);

    private final int code;

    CircuitBreakerState(int code) {
        this.code = code;
    }

    /** @return the numeric code published as the Prometheus gauge value. */
    public int getCode() {
        return code;
    }
}
