package com.ratelimitx.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;
import com.ratelimitx.core.circuitbreaker.LocalRateLimiter;
import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;

/**
 * Dispatches a rate limit check to the configured algorithm, behind the circuit breaker.
 *
 * <p>Every algorithm receives the caller's own limit, so the limit enforced is the one the
 * response headers advertise.
 */
@Service
public class ResilientRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private final CircuitBreaker circuitBreaker;
    private final LocalRateLimiter localRateLimiter;
    private final RateLimiterService fixedWindowService;
    private final TokenBucketService tokenBucketService;
    private final SlidingWindowService slidingWindowService;
    private final RateLimitConfig config;

    public ResilientRateLimiter(CircuitBreaker circuitBreaker,
            LocalRateLimiter localRateLimiter,
            RateLimiterService fixedWindowService,
            TokenBucketService tokenBucketService,
            SlidingWindowService slidingWindowService,
            RateLimitConfig config) {
        this.circuitBreaker = circuitBreaker;
        this.localRateLimiter = localRateLimiter;
        this.fixedWindowService = fixedWindowService;
        this.tokenBucketService = tokenBucketService;
        this.slidingWindowService = slidingWindowService;
        this.config = config;
    }

    /** Checks using the configured default limit. */
    public RateLimitResult checkRateLimit(String userId) {
        return checkRateLimit(userId, config.getMaxRequests());
    }

    /**
     * Checks against the caller's limit.
     *
     * @param limit requests allowed per minute for this caller
     */
    public RateLimitResult checkRateLimit(String userId, int limit) {
        // Read the algorithm once so a switch mid-check cannot send the primary path and the
        // fallback down different algorithms.
        String algorithm = config.getAlgorithm();
        return circuitBreaker.execute(
                () -> executeRedis(algorithm, userId, limit),
                () -> executeLocalFallback(userId, limit));
    }

    private RateLimitResult executeRedis(String algorithm, String userId, int limit) {
        return switch (algorithm) {
            case RateLimitConfig.TOKEN_BUCKET -> tokenBucketService.tryConsume(userId, limit);
            case RateLimitConfig.SLIDING_WINDOW -> slidingWindowService.checkRateLimit(userId, limit);
            case RateLimitConfig.FIXED_WINDOW -> fixedWindowService.checkWithInfo(userId, limit);
            default -> throw new UnsupportedOperationException(
                    "No rate limiter for algorithm: " + algorithm);
        };
    }

    private RateLimitResult executeLocalFallback(String userId, int limit) {
        logger.debug("Using local fallback for user {}", userId);
        return localRateLimiter.isAllowed(userId, limit);
    }

    public boolean isUsingFallback() {
        return !circuitBreaker.isAllowingRequests();
    }

    /**
     * @return the algorithm currently deciding requests, or {@code local-fallback} while the
     *         circuit is open. This is what the {@code X-Algorithm} response header reports.
     */
    public String getCurrentMode() {
        if (isUsingFallback()) {
            return "local-fallback";
        }
        return config.getAlgorithm();
    }
}
