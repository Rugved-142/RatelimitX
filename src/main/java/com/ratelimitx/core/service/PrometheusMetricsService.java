package com.ratelimitx.core.service;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Records rate limiting metrics into Micrometer.
 *
 * <p>Every tag used here has a small, fixed set of values. Tagging by user id would create one
 * Prometheus time series per user and grow without bound, so these metrics deliberately carry no
 * per-user label. Per-user state is available from the admin endpoints instead.
 *
 * <p>Every method here swallows its own exceptions: a metrics failure must not fail the request
 * that was being measured. Callers therefore do not need to guard these calls.
 *
 * <p>Each call builds its meter and registers it. Micrometer returns the meter already held for
 * that name and tag set rather than creating a second one, so this costs a lookup, not a meter.
 */
@Service
public class PrometheusMetricsService {

    private static final String REQUESTS_TOTAL = "ratelimit.requests.total";
    private static final String REQUESTS_DENIED = "ratelimit.requests.denied";
    private static final String CHECK_DURATION = "ratelimit.check.duration";

    private final MeterRegistry meterRegistry;

    public PrometheusMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records one rate limit decision.
     *
     * @param allowed whether the request was allowed
     * @param algorithm the algorithm that made the decision, or {@code local-fallback}
     */
    public void recordRequest(boolean allowed, String algorithm) {
        try {
            Counter.builder(REQUESTS_TOTAL)
                    .description("Rate limit decisions")
                    .tags(Tags.of(
                            "allowed", String.valueOf(allowed),
                            "algorithm", algorithm != null ? algorithm : "unknown"))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            // Metrics are non-critical.
        }
    }

    /**
     * Records one denied request.
     *
     * @param algorithm the algorithm that denied it, or {@code local-fallback}
     */
    public void recordDenied(String algorithm) {
        try {
            Counter.builder(REQUESTS_DENIED)
                    .description("Denied requests")
                    .tags(Tags.of("algorithm", algorithm != null ? algorithm : "unknown"))
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            // Metrics are non-critical.
        }
    }

    /**
     * Records how long a rate limit check took.
     *
     * @param durationMs duration in milliseconds
     * @param algorithm the algorithm used, or {@code local-fallback}
     * @param allowed whether the request was allowed
     */
    public void recordCheckDuration(long durationMs, String algorithm, boolean allowed) {
        try {
            Timer.builder(CHECK_DURATION)
                    .description("Time taken to perform a rate limit check")
                    .tags(Tags.of(
                            "algorithm", algorithm != null ? algorithm : "unknown",
                            "result", allowed ? "allowed" : "denied"))
                    .register(meterRegistry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Metrics are non-critical.
        }
    }
}
