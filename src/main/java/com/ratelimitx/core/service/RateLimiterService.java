package com.ratelimitx.core.service;

import java.util.Collections;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;

/**
 * Fixed window counter.
 *
 * <p>Counts requests in wall-clock windows of {@code ratelimit.window-size-seconds}. The count,
 * the expiry and the window arithmetic all happen inside one Lua script, so two concurrent
 * requests cannot both see a fresh counter.
 *
 * <p>The window boundary is computed from Redis's own clock, not the caller's, so several
 * application instances agree on which window a request belongs to even if their system clocks
 * differ.
 *
 * <p>Denied requests still increment the counter, which is the usual behaviour for this
 * algorithm: a client that keeps hammering stays denied for the rest of the window.
 */
@Service
public class RateLimiterService {

    private static final String ALGORITHM = "fixed-window";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitConfig config;
    private final DefaultRedisScript<List> script;

    public RateLimiterService(StringRedisTemplate redisTemplate, RateLimitConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.script = new DefaultRedisScript<>(luaScript(), List.class);
    }

    /** Checks against the configured default limit. */
    public RateLimitResult checkWithInfo(String userId) {
        return checkWithInfo(userId, config.getMaxRequests());
    }

    /**
     * Checks against an explicit limit, normally the caller's tier limit.
     *
     * @param maxRequests requests allowed per window
     */
    public RateLimitResult checkWithInfo(String userId, int maxRequests) {
        String prefix = RateLimitKeys.fixedWindowPrefix(userId);

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                script,
                Collections.singletonList(prefix),
                String.valueOf(maxRequests),
                String.valueOf(config.getWindowSizeSeconds()));

        if (result == null) {
            throw new RateLimitScriptException(ALGORITHM, prefix);
        }

        boolean allowed = result.get(0) == 1L;
        int remaining = result.get(1).intValue();
        long resetMillis = result.get(2);

        return new RateLimitResult(allowed, maxRequests, remaining, resetMillis);
    }

    /** Current counter value for one user, without consuming a request. */
    public int currentCount(String userId) {
        long windowMillis = config.getWindowSizeSeconds() * 1000L;
        long window = redisNowMillis() / windowMillis;
        String value = redisTemplate.opsForValue()
                .get(RateLimitKeys.fixedWindowPrefix(userId) + ":" + window);
        return value != null ? Integer.parseInt(value) : 0;
    }

    /** Milliseconds remaining in the current window. */
    public long millisUntilReset() {
        long windowMillis = config.getWindowSizeSeconds() * 1000L;
        long now = redisNowMillis();
        return windowMillis - (now % windowMillis);
    }

    /**
     * Redis server time in milliseconds. Read from Redis rather than the local clock so read-only
     * views agree with what the scripts enforced.
     */
    private long redisNowMillis() {
        Long time = redisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<Long>) connection ->
                        connection.serverCommands().time());
        return time != null ? time : System.currentTimeMillis();
    }

    private String luaScript() {
        return """
            -- KEYS[1] is the per-user prefix, which carries the hash tag. The window number is
            -- appended here instead of by the caller, because the clock is read from Redis; the
            -- resulting key stays in the same cluster slot as the prefix.
            local prefix = KEYS[1]
            local max_requests = tonumber(ARGV[1])
            local window_seconds = tonumber(ARGV[2])

            local time = redis.call('TIME')
            local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

            local window_ms = window_seconds * 1000
            local window_id = math.floor(now_ms / window_ms)
            local key = prefix .. ':' .. window_id

            local count = redis.call('INCR', key)
            if count == 1 then
                -- Only the request that created the counter sets the expiry, so a long-running
                -- window is never extended by later requests.
                redis.call('PEXPIRE', key, window_ms)
            end

            local allowed = 0
            if count <= max_requests then
                allowed = 1
            end

            local remaining = max_requests - count
            if remaining < 0 then
                remaining = 0
            end

            local reset_ms = (window_id + 1) * window_ms - now_ms

            return {allowed, remaining, reset_ms}
            """;
    }
}
