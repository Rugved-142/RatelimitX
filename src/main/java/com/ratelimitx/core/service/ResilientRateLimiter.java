package com.ratelimitx.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;
import com.ratelimitx.core.circuitbreaker.LocalRateLimiter;
import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;



/**
 * Resilient rate limiter that uses Circuit Breaker pattern.
 *
 * - When Redis is UP: Uses Redis-based rate limiting (distributed)
 * - When Redis is DOWN: Falls back to local in-memory rate limiting
 */
@Service
public class ResilientRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(ResilientRateLimiter.class);

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private LocalRateLimiter localRateLimiter;

    @Autowired
    private RateLimiterService fixedWindowService;

    @Autowired
    private TokenBucketService tokenBucketService;

    @Autowired
    private SlidingWindowService slidingWindowService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("rateLimitConfig")
    private RateLimitConfig config;

    /**
     * Check rate limit with circuit breaker protection.
     *
     * This is the main method to use instead of calling services directly.
     */
    public RateLimitResult checkRateLimit(String userId) {

        return circuitBreaker.execute(
            // Primary operation: Try Redis
            () -> executeRedisRateLimit(userId),

            // Fallback operation: Use local memory
            () -> executeLocalFallback(userId)
        );
    }

    /**
     * Execute rate limit check using Redis (primary)
     */
    private RateLimitResult executeRedisRateLimit(String userId) {
        return switch (config.getAlgorithm()) {
            case "token-bucket" -> tokenBucketService.tryConsume(userId);
            case "sliding-window" -> slidingWindowService.checkRateLimit(userId);
            default -> fixedWindowService.checkWithInfo(userId);
        };
    }

    /**
     * Execute rate limit check using local memory (fallback)
     */
    private RateLimitResult executeLocalFallback(String userId) {
        logger.debug("Using local fallback for user: {}", userId);
        return localRateLimiter.checkRateLimit(userId);
    }

    /**
     * Check if currently using fallback
     */
    public boolean isUsingFallback() {
        return !circuitBreaker.isAllowingRequests();
    }

    /**
     * Get current algorithm name (or "local-fallback" if circuit is open)
     */
    public String getCurrentMode() {
        if (isUsingFallback()) {
            return "local-fallback";
        }
        return config.getAlgorithm();
    }
}