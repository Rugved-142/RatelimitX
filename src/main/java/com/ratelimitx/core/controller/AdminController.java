package com.ratelimitx.core.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;
import com.ratelimitx.core.circuitbreaker.LocalRateLimiter;
import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.service.ResilientRateLimiter;
import com.ratelimitx.core.service.SlidingWindowService;
import com.ratelimitx.core.service.TokenBucketService;


@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenBucketService tokenBucketService;

    @Autowired
    private SlidingWindowService slidingWindowService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("rateLimitConfig")
    private RateLimitConfig config;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private LocalRateLimiter localRateLimiter;

    @Autowired
    private ResilientRateLimiter resilientRateLimiter;

    private static final long START_TIME = System.currentTimeMillis();

    // ==================== SYSTEM ENDPOINTS ====================

    @GetMapping("/stats")
    public Map<String, Object> getStats() {

        Map<String, Object> stats = new HashMap<>();

        // Count all rate limit keys across all algorithms
        Set<String> rateLimitKeys = redis.keys("rate:*");
        Set<String> bucketKeys = redis.keys("bucket:*");
        Set<String> slidingKeys = redis.keys("sliding:*");

        Set<String> uniqueUsers = new HashSet<>();

        // Extract users from fixed window keys
        if (rateLimitKeys != null) {
            for (String key : rateLimitKeys) {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    uniqueUsers.add(parts[1]);
                }
            }
        }

        // Extract users from bucket keys
        if (bucketKeys != null) {
            for (String key : bucketKeys) {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    uniqueUsers.add(parts[1]);
                }
            }
        }

        // Extract users from sliding window keys
        if (slidingKeys != null) {
            for (String key : slidingKeys) {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    uniqueUsers.add(parts[1]);
                }
            }
        }

        int totalKeys = (rateLimitKeys != null ? rateLimitKeys.size() : 0)
                + (bucketKeys != null ? bucketKeys.size() : 0)
                + (slidingKeys != null ? slidingKeys.size() : 0);

        stats.put("activeUsers", uniqueUsers.size());
        stats.put("totalActiveKeys", totalKeys);
        stats.put("activeAlgorithm", config.getAlgorithm());

        stats.put("uptimeSeconds", (System.currentTimeMillis() - START_TIME) / 1000);
        stats.put("currentTime", new Date());

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        stats.put("memoryUsedMB", usedMemory);
        stats.put("memoryMaxMB", maxMemory);

        return stats;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();

        try {
            redis.opsForValue().set("health", "ok");
            health.put("redis", "UP");
            health.put("status", "HEALTHY");
            health.put("algorithm", config.getAlgorithm());
        } catch (Exception e) {
            health.put("redis", "DOWN");
            health.put("status", "UNHEALTHY");
            health.put("error", e.getMessage());
        }
        return health;
    }

    // ==================== FIXED WINDOW ENDPOINTS ====================

    @GetMapping("/user/{userId}")
    public Map<String, Object> getUserStatus(@PathVariable String userId) {
        Map<String, Object> status = new HashMap<>();

        long currentMinute = System.currentTimeMillis() / 60000;
        String key = "rate:" + userId + ":" + currentMinute;

        String count = redis.opsForValue().get(key);
        int currentCount = count != null ? Integer.parseInt(count) : 0;

        int maxRequests = getUserLimit(userId);

        status.put("userId", userId);
        status.put("algorithm", "fixed-window");
        status.put("currentRequests", currentCount);
        status.put("maxRequests", maxRequests);
        status.put("remainingRequests", Math.max(0, maxRequests - currentCount));

        int secondsIntoMinute = (int) ((System.currentTimeMillis() / 1000) % 60);
        status.put("resetsInSeconds", 60 - secondsIntoMinute);

        status.put("isRateLimited", currentCount >= maxRequests);

        return status;
    }

    // ==================== TOKEN BUCKET ENDPOINTS ====================

    @GetMapping("/bucket/{userId}")
    public Map<String, Object> getBucketStatus(@PathVariable String userId) {
        RateLimitResult result = tokenBucketService.getBucketStatus(userId);

        Map<String, Object> status = new HashMap<>();
        status.put("userId", userId);
        status.put("algorithm", "token-bucket");
        status.put("tokensRemaining", result.getRemaining());
        status.put("bucketCapacity", result.getLimit());
        status.put("isAllowed", result.isAllowed());

        return status;
    }

    // ==================== SLIDING WINDOW ENDPOINTS ====================

    @GetMapping("/sliding/{userId}")
    public Map<String, Object> getSlidingWindowStatus(@PathVariable String userId) {
        RateLimitResult result = slidingWindowService.getStatus(userId);

        Map<String, Object> status = new HashMap<>();
        status.put("userId", userId);
        status.put("algorithm", "sliding-window");
        status.put("currentCount", result.getLimit() - result.getRemaining());
        status.put("maxRequests", result.getLimit());
        status.put("remainingRequests", result.getRemaining());
        status.put("resetInMs", result.getResetTime());
        status.put("isAllowed", result.isAllowed());

        return status;
    }

    // ==================== COMPARISON ENDPOINT ====================

    @GetMapping("/compare/{userId}")
    public Map<String, Object> compareAlgorithms(@PathVariable String userId) {
        Map<String, Object> comparison = new HashMap<>();

        // Fixed Window Status
        Map<String, Object> fixedWindow = new HashMap<>();
        long currentMinute = System.currentTimeMillis() / 60000;
        String key = "rate:" + userId + ":" + currentMinute;
        String count = redis.opsForValue().get(key);
        int currentCount = count != null ? Integer.parseInt(count) : 0;
        int maxRequests = getUserLimit(userId);
        fixedWindow.put("currentRequests", currentCount);
        fixedWindow.put("maxRequests", maxRequests);
        fixedWindow.put("remaining", Math.max(0, maxRequests - currentCount));
        comparison.put("fixedWindow", fixedWindow);

        // Token Bucket Status
        RateLimitResult bucketResult = tokenBucketService.getBucketStatus(userId);
        Map<String, Object> tokenBucket = new HashMap<>();
        tokenBucket.put("tokensRemaining", bucketResult.getRemaining());
        tokenBucket.put("bucketCapacity", bucketResult.getLimit());
        tokenBucket.put("isAllowed", bucketResult.isAllowed());
        comparison.put("tokenBucket", tokenBucket);

        // Sliding Window Status
        RateLimitResult slidingResult = slidingWindowService.getStatus(userId);
        Map<String, Object> slidingWindow = new HashMap<>();
        slidingWindow.put("currentCount", slidingResult.getLimit() - slidingResult.getRemaining());
        slidingWindow.put("maxRequests", slidingResult.getLimit());
        slidingWindow.put("remaining", slidingResult.getRemaining());
        comparison.put("slidingWindow", slidingWindow);

        // Active algorithm
        comparison.put("activeAlgorithm", config.getAlgorithm());

        return comparison;
    }

    // ==================== USER MANAGEMENT ENDPOINTS ====================

    @PostMapping("/limit")
    public Map<String, Object> setUserLimit(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("userId");
        Integer limit = (Integer) request.get("limit");

        redis.opsForHash().put("user-limits", userId, String.valueOf(limit));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("userId", userId);
        response.put("newLimit", limit);
        return response;
    }

    @DeleteMapping("/reset/{userId}")
    public Map<String, Object> resetUser(@PathVariable String userId) {

        // Reset Fixed Window keys
        Set<String> fixedWindowKeys = redis.keys("rate:" + userId + ":*");
        if (fixedWindowKeys != null && !fixedWindowKeys.isEmpty()) {
            redis.delete(fixedWindowKeys);
        }

        // Reset Token Bucket keys
        Set<String> bucketKeys = redis.keys("bucket:" + userId);
        if (bucketKeys != null && !bucketKeys.isEmpty()) {
            redis.delete(bucketKeys);
        }

        // Reset Sliding Window keys
        Set<String> slidingKeys = redis.keys("sliding:" + userId + ":*");
        if (slidingKeys != null && !slidingKeys.isEmpty()) {
            redis.delete(slidingKeys);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Reset user: " + userId + " (all algorithms)");
        return response;
    }

    //==================== Circuit Breaer ENDPOINTS ====================
    @GetMapping("/circuit")
    public Map<String, Object> getCircuitBreakerStatus() {
        Map<String, Object> status = circuitBreaker.getStatus();
        status.put("currentMode", resilientRateLimiter.getCurrentMode());
        status.put("localFallbackActiveUsers", localRateLimiter.getActiveUsers());
        return status;
    }

    @PostMapping("/circuit/reset")
    public Map<String, Object> resetCircuitBreaker() {
        circuitBreaker.reset();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Circuit breaker reset to CLOSED state");
        response.put("currentState", circuitBreaker.getState().toString());
        return response;
    }

    // ==================== HELPER METHODS ====================

    private int getUserLimit(String userId) {
        String customLimit = (String) redis.opsForHash().get("user-limits", userId);
        return customLimit != null ? Integer.parseInt(customLimit) : 10;
    }
}