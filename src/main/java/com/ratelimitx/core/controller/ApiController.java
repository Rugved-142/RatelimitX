package com.ratelimitx.core.controller;

import java.time.Instant;
import java.util.UUID;

import com.ratelimitx.core.events.RateLimitEvent;
import com.ratelimitx.core.events.RateLimitEventPublisher;
import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.security.AuthenticatedUser;
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
 * {@code JwtAuthenticationFilter} populated when it validated the token.
 *
 * <p>Nothing in this class queries Postgres, and the rate limit decision itself touches Redis
 * only - but the request as a whole does reach the database: the filter that built the principal
 * loaded it with {@code CustomUserDetailsService}, which reads the users table on every
 * authenticated request. A Postgres outage therefore fails the request before it gets here.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ResilientRateLimiter resilientRateLimiter;
    private final PrometheusMetricsService prometheusMetricsService;
    private final RateLimitEventPublisher eventPublisher;

    public ApiController(
            ResilientRateLimiter resilientRateLimiter,
            PrometheusMetricsService prometheusMetricsService,
            RateLimitEventPublisher eventPublisher
    ) {
        this.resilientRateLimiter = resilientRateLimiter;
        this.prometheusMetricsService = prometheusMetricsService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/data")
    public ResponseEntity<String> getData(@AuthenticationPrincipal AuthenticatedUser user) {

        long startTime = System.currentTimeMillis();

        String userId = user.getUsername();
        int limit = user.getRateLimit();

        RateLimitResult result = resilientRateLimiter.checkRateLimit(userId, limit);

        long responseTime = System.currentTimeMillis() - startTime;
        String algorithm = resilientRateLimiter.getCurrentMode();

        // Both of these are deliberately incapable of failing a request: the metrics service
        // swallows its own exceptions, and the publisher only offers to a bounded queue.
        eventPublisher.publish(new RateLimitEvent(UUID.randomUUID(), Instant.now(), userId,
                user.getRole().name(), algorithm, result.isAllowed(), result.getRemaining()));

        prometheusMetricsService.recordRequest(result.isAllowed(), algorithm);
        prometheusMetricsService.recordCheckDuration(responseTime, algorithm, result.isAllowed());
        if (!result.isAllowed()) {
            prometheusMetricsService.recordDenied(algorithm);
        }

        // Report the limit the limiter actually enforced, not the one we asked for: if the two
        // ever diverge, the header should show what happened rather than what was intended.
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
                .status(result.isAllowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Limit", String.valueOf(result.getLimit()))
                .header("X-RateLimit-Remaining", String.valueOf(result.getRemaining()))
                .header("X-RateLimit-Reset", String.valueOf(result.getResetTime()))
                .header("X-Algorithm", algorithm)
                .header("X-User-Role", user.getRole().name());

        if (!result.isAllowed()) {
            responseBuilder.header("Retry-After", String.valueOf(result.retryAfterSeconds()));
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
}
