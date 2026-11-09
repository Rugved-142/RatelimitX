package com.ratelimitx.core.controller;

import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.security.AuthenticatedUser;
import com.ratelimitx.core.service.MetricsService;
import com.ratelimitx.core.service.PrometheusMetricsService;
import com.ratelimitx.core.service.ResilientRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rate-limited API.
 *
 * <p>The caller's identity, role and rate limit come from {@link AuthenticatedUser}, which
 * {@code JwtAuthenticationFilter} populated when it validated the token. No endpoint here queries
 * Postgres: the per-request path touches Redis only.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ResilientRateLimiter resilientRateLimiter;
    private final MetricsService metricsService;
    private final PrometheusMetricsService prometheusMetricsService;

    public ApiController(
            ResilientRateLimiter resilientRateLimiter,
            MetricsService metricsService,
            PrometheusMetricsService prometheusMetricsService
    ) {
        this.resilientRateLimiter = resilientRateLimiter;
        this.metricsService = metricsService;
        this.prometheusMetricsService = prometheusMetricsService;
    }

    @GetMapping("/data")
    public ResponseEntity<String> getData(@AuthenticationPrincipal AuthenticatedUser user) {

        long startTime = System.currentTimeMillis();

        String userId = user.getUsername();
        int limit = user.getRateLimit();

        RateLimitResult result = resilientRateLimiter.checkRateLimit(userId, limit);

        long responseTime = System.currentTimeMillis() - startTime;
        String algorithm = resilientRateLimiter.getCurrentMode();

        // Metrics are non-critical: a failure here must not fail the request.
        try {
            prometheusMetricsService.recordRequest(result.isAllowed(), algorithm);
            prometheusMetricsService.recordCheckDuration(responseTime, algorithm, result.isAllowed());

            if (!result.isAllowed()) {
                prometheusMetricsService.recordDenied(algorithm);
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }

        try {
            metricsService.recordRequest(userId, result.isAllowed(), responseTime);
        } catch (Exception e) {
            // Intentionally ignored.
        }

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
                .status(result.isAllowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Limit", String.valueOf(limit))
                .header("X-RateLimit-Remaining", String.valueOf(result.getRemaining()))
                .header("X-RateLimit-Reset", String.valueOf(result.getResetTime()))
                .header("X-Algorithm", algorithm)
                .header("X-User-Role", user.getRole().name());

        if (!result.isAllowed()) {
            responseBuilder.header("Retry-After", String.valueOf(retryAfterSeconds(result)));
            return responseBuilder.body("Rate limit exceeded. Retry after " + result.getResetTime() + "ms");
        }

        return responseBuilder.body("Success! Here's your data");
    }

    /**
     * Current rate limit configuration for the caller.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(java.util.Map.of(
                "username", user.getUsername(),
                "role", user.getRole().name(),
                "rateLimit", user.getRateLimit(),
                "algorithm", resilientRateLimiter.getCurrentMode()
        ));
    }

    /**
     * {@code Retry-After} in seconds, rounded up and never below 1, so a denied client is not told
     * to retry immediately.
     *
     * <p>Assumes {@code resetTime} is milliseconds; phase 3 makes that uniform across algorithms.
     */
    private long retryAfterSeconds(RateLimitResult result) {
        long resetMillis = result.getResetTime();
        if (resetMillis <= 0) {
            return 1L;
        }
        return Math.max(1L, (resetMillis + 999L) / 1000L);
    }
}
