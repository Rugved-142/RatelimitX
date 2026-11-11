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
import com.ratelimitx.core.dto.UpdateUserLimitRequest;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.service.RateLimitKeys;
import com.ratelimitx.core.service.RateLimiterService;
import com.ratelimitx.core.service.ResilientRateLimiter;
import com.ratelimitx.core.web.UserNotFoundException;

import jakarta.validation.Valid;




@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired
    private StringRedisTemplate redis;

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

    @Autowired
    private UserRepository userRepository;

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

    // ==================== USER MANAGEMENT ENDPOINTS ====================

    /**
     * Sets the rate limit, and optionally the role, that will actually be enforced for a user.
     *
     * <p>This used to write the value into a Redis hash called {@code user-limits}. Nothing on the
     * request path ever read that hash: the limit applied to {@code /api/data} comes from the
     * authenticated principal, which is loaded from the users table. So the endpoint reported
     * success, the admin views below echoed the new number back, and the limit the caller actually
     * received never changed. It now updates the user row, which is the value the limiter uses.
     */
    @PostMapping("/limit")
    public Map<String, Object> setUserLimit(@Valid @RequestBody UpdateUserLimitRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new UserNotFoundException(request.getUsername()));

        if (request.getRole() != null) {
            user.setRole(request.getRole());
            // Without an explicit limit, move the user onto their new tier's default.
            if (request.getRateLimit() == null) {
                user.setRateLimit(request.getRole().getDefaultRateLimit());
            }
        }
        if (request.getRateLimit() != null) {
            user.setRateLimit(request.getRateLimit());
        }

        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("username", user.getUsername());
        response.put("role", user.getRole().name());
        response.put("rateLimit", user.getRateLimit());
        // An existing token still carries the old claims; the limiter reads the user row on each
        // request, so the new limit applies immediately regardless.
        response.put("note", "Applies to the next request; existing tokens remain valid.");
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

    /**
     * The limit that would actually be enforced for a user, read from the same place the request
     * path reads it. The admin views below must not report a different number from the one the
     * limiter applies.
     */
    private int getUserLimit(String userId) {
        return userRepository.findByUsername(userId)
                .map(user -> user.getRateLimit() != null
                        ? user.getRateLimit()
                        : user.getRole().getDefaultRateLimit())
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}