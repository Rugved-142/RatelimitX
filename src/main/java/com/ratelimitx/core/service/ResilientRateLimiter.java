package com.ratelimitx.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;
import com.ratelimitx.core.circuitbreaker.LocalRateLimiter;
import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;




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

    public RateLimitResult checkRateLimit(String userId) {

        return circuitBreaker.execute(
            () -> executeRedisRateLimit(userId),

            () -> executeLocalFallback(userId)
        );
    }

    /**
     * Check rate limit with custom limit (based on user tier)
     */
    public RateLimitResult checkRateLimit(String userId, int customLimit) {
        return circuitBreaker.execute(
                () -> executeRedisRateLimit(userId, customLimit),
                () -> executeLocalFallback(userId, customLimit)
        );
    }

    private RateLimitResult executeRedisRateLimit(String userId) {
        return switch (config.getAlgorithm()) {
            case "token-bucket" -> tokenBucketService.tryConsume(userId);
            case "sliding-window" -> slidingWindowService.checkRateLimit(userId);
            default -> fixedWindowService.checkWithInfo(userId);
        };
    }

    private RateLimitResult executeRedisRateLimit(String userId, int customLimit) {
        // Use customLimit instead of default
        String currentAlgorithm = config.getAlgorithm();
        
        switch (currentAlgorithm) {
            case "token-bucket":
                // Token bucket doesn't support custom limits in this version, use default
                return tokenBucketService.tryConsume(userId);
            case "sliding-window":
                // Sliding window supports custom parameters
                return slidingWindowService.checkRateLimit(userId, customLimit, 60);
            case "fixed-window":
            default:
                // Fixed window with custom limit
                return fixedWindowService.checkWithInfo(userId, customLimit);
        }
    }

    private RateLimitResult executeLocalFallback(String userId) {
        logger.debug("Using local fallback for user: {}", userId);
        return localRateLimiter.checkRateLimit(userId);
    }

    private RateLimitResult executeLocalFallback(String userId, int customLimit) {
        return localRateLimiter.isAllowed(userId, customLimit);
    }

    public boolean isUsingFallback() {
        return !circuitBreaker.isAllowingRequests();
    }

    public String getCurrentMode() {
        if (isUsingFallback()) {
            return "local-fallback";
        }
        return config.getAlgorithm();
    }
}