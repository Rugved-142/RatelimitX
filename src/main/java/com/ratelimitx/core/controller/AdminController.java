package com.ratelimitx.core.controller;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.ratelimitx.core.service.RateLimitKeys;
import com.ratelimitx.core.service.RateLimiterService;
import com.ratelimitx.core.service.ResilientRateLimiter;
import com.ratelimitx.core.service.SlidingWindowService;
import com.ratelimitx.core.service.TokenBucketService;




@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenBucketService tokenBucketService;

    @Autowired
    private SlidingWindowService slidingWindowService;

    @Autowired
    private RateLimitConfig config;

    @Autowired
    private CircuitBreaker circuitBreaker;

    @Autowired
    private LocalRateLimiter localRateLimiter;

    @Autowired
    private ResilientRateLimiter resilientRateLimiter;

    @Autowired
    private RateLimiterService fixedWindowService;

    /**
     * Upper bound on keys returned by one administrative scan. SCAN is incremental, but an
     * unbounded walk of a large keyspace still builds an unbounded result in memory.
     */
    @Value("${ratelimit.admin.scan-limit:10000}")
    private int scanLimit;

    /** Keys requested per SCAN round trip. A hint to Redis, not a guarantee. */
    @Value("${ratelimit.admin.scan-batch:500}")
    private int scanBatch;

    private static final long START_TIME = System.currentTimeMillis();

    /**
     * Walks the keyspace with SCAN instead of KEYS.
     *
     * <p>KEYS is O(N) over the entire keyspace and blocks the single-threaded Redis server for
     * the whole walk, so on a production-sized keyspace it stalls every other client, including
     * the rate limit checks on the request path. SCAN returns a cursor and yields between
     * batches.
     *
     * @return at most {@code scanLimit} matching keys
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(scanBatch).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && keys.size() < scanLimit) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    // ==================== SYSTEM ENDPOINTS ====================

    @GetMapping("/stats")
    public Map<String, Object> getStats() {

        Map<String, Object> stats = new HashMap<>();

        Set<String> rateLimitKeys = scanKeys(RateLimitKeys.FIXED_WINDOW_SCAN);
        Set<String> bucketKeys = scanKeys(RateLimitKeys.BUCKET_SCAN);
        Set<String> slidingKeys = scanKeys(RateLimitKeys.SLIDING_WINDOW_SCAN);

        Set<String> uniqueUsers = new HashSet<>();
        for (String key : rateLimitKeys) {
            addUser(uniqueUsers, key);
        }
        for (String key : bucketKeys) {
            addUser(uniqueUsers, key);
        }
        for (String key : slidingKeys) {
            addUser(uniqueUsers, key);
        }

        int totalKeys = rateLimitKeys.size() + bucketKeys.size() + slidingKeys.size();

        // Say so rather than presenting a capped count as a complete one.
        boolean truncated = rateLimitKeys.size() >= scanLimit
                || bucketKeys.size() >= scanLimit
                || slidingKeys.size() >= scanLimit;
        stats.put("truncated", truncated);
        stats.put("scanLimit", scanLimit);

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

    /**
     * Operational health of the rate limiter's Redis dependency. ADMIN only, like the rest of
     * {@code /admin/**}: it reports internal state and writes a probe key to Redis.
     */
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

    /**
     * Records the user a key belongs to, skipping the auth throttle's keys: those are keyed by
     * client IP, so counting them would inflate the active user total with addresses.
     */
    private void addUser(Set<String> users, String key) {
        String userId = RateLimitKeys.userIdOf(key);
        if (userId != null && !userId.startsWith(RateLimitKeys.AUTH_THROTTLE_PREFIX)) {
            users.add(userId);
        }
    }

    // ==================== ALGORITHM SELECTION ====================

    /** Reports the algorithm currently deciding requests and the names accepted by POST. */
    @GetMapping("/algorithm")
    public Map<String, Object> getAlgorithm() {
        Map<String, Object> response = new HashMap<>();
        response.put("algorithm", config.getAlgorithm());
        response.put("currentMode", resilientRateLimiter.getCurrentMode());
        response.put("supported", RateLimitConfig.sortedNames());
        return response;
    }

    /**
     * Switches the active algorithm without a restart.
     *
     * <p>An unknown name is rejected with 400 and changes nothing. Existing Redis state is left
     * alone: each algorithm uses its own keys, so a switch does not reset anyone's counters, and
     * switching back resumes from where that algorithm left off. Requests already in flight
     * finish under the algorithm they started with.
     */
    @PostMapping("/algorithm")
    public Map<String, Object> setAlgorithm(@RequestBody Map<String, Object> request) {
        Object requested = request.get("algorithm");
        String name = requested == null ? null : String.valueOf(requested);

        String previous = config.setAlgorithmChecked(name);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("previousAlgorithm", previous);
        response.put("algorithm", config.getAlgorithm());
        return response;
    }

    // ==================== FIXED WINDOW ENDPOINTS ====================

    @GetMapping("/user/{userId}")
    public Map<String, Object> getUserStatus(@PathVariable String userId) {
        Map<String, Object> status = new HashMap<>();

        int currentCount = fixedWindowService.currentCount(userId);
        int maxRequests = getUserLimit(userId);
        long resetMillis = fixedWindowService.millisUntilReset();

        status.put("userId", userId);
        status.put("algorithm", "fixed-window");
        status.put("currentRequests", currentCount);
        status.put("maxRequests", maxRequests);
        status.put("remainingRequests", Math.max(0, maxRequests - currentCount));
        status.put("resetInMs", resetMillis);
        status.put("isRateLimited", currentCount >= maxRequests);

        return status;
    }

    // ==================== TOKEN BUCKET ENDPOINTS ====================

    @GetMapping("/bucket/{userId}")
    public Map<String, Object> getBucketStatus(@PathVariable String userId) {
        RateLimitResult result = tokenBucketService.getBucketStatus(userId, getUserLimit(userId));

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
        RateLimitResult result = slidingWindowService.getStatus(
                userId, getUserLimit(userId), config.getWindowSizeSeconds());

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
        int currentCount = fixedWindowService.currentCount(userId);
        int maxRequests = getUserLimit(userId);
        fixedWindow.put("currentRequests", currentCount);
        fixedWindow.put("maxRequests", maxRequests);
        fixedWindow.put("remaining", Math.max(0, maxRequests - currentCount));
        comparison.put("fixedWindow", fixedWindow);

        // Token Bucket Status
        RateLimitResult bucketResult = tokenBucketService.getBucketStatus(userId, maxRequests);
        Map<String, Object> tokenBucket = new HashMap<>();
        tokenBucket.put("tokensRemaining", bucketResult.getRemaining());
        tokenBucket.put("bucketCapacity", bucketResult.getLimit());
        tokenBucket.put("isAllowed", bucketResult.isAllowed());
        comparison.put("tokenBucket", tokenBucket);

        // Sliding Window Status
        RateLimitResult slidingResult = slidingWindowService.getStatus(
                userId, maxRequests, config.getWindowSizeSeconds());
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

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(scanKeys(RateLimitKeys.fixedWindowPattern(userId)));
        keys.addAll(scanKeys(RateLimitKeys.slidingWindowPattern(userId)));
        keys.add(RateLimitKeys.bucket(userId));

        long deleted = keys.isEmpty() ? 0 : redis.delete(keys);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("userId", userId);
        response.put("keysDeleted", deleted);
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