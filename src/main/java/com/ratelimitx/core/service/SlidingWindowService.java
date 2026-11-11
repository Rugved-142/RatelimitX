package com.ratelimitx.core.service;

import java.util.Collections;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;

/**
 * Sliding window counter.
 *
 * <p>Weights the previous window's count by how much of it still overlaps the trailing window, and
 * adds the current window's count, which avoids the burst a fixed window allows across its
 * boundary.
 *
 * <p>The three keys share a hash tag so a cluster keeps them on one node; see
 * {@link RateLimitKeys}. Time comes from Redis, so instances cannot disagree about where the
 * window boundary falls.
 */
@Service
public class SlidingWindowService {

    private static final String ALGORITHM = "sliding-window";

    private final StringRedisTemplate redisTemplate;
    private final RateLimitConfig config;
    private final DefaultRedisScript<List> script;

    public SlidingWindowService(StringRedisTemplate redisTemplate, RateLimitConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.script = new DefaultRedisScript<>(luaScript(), List.class);
    }

    /** Checks against the configured default limit and window. */
    public RateLimitResult checkRateLimit(String userId) {
        return checkRateLimit(userId, config.getMaxRequests(), config.getWindowSizeSeconds());
    }

    /** Checks against an explicit limit over the configured window. */
    public RateLimitResult checkRateLimit(String userId, int maxRequests) {
        return checkRateLimit(userId, maxRequests, config.getWindowSizeSeconds());
    }

    /**
     * @param maxRequests requests allowed per window
     * @param windowSeconds window length in <strong>seconds</strong>
     */
    public RateLimitResult checkRateLimit(String userId, int maxRequests, int windowSeconds) {
        String prefix = RateLimitKeys.slidingWindowPrefix(userId);

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                script,
                Collections.singletonList(prefix),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds),
                "1");

        if (result == null) {
            throw new RateLimitScriptException(ALGORITHM, prefix);
        }

        boolean allowed = result.get(0) == 1L;
        int weightedCount = result.get(1).intValue();
        long resetMillis = result.get(2);

        return new RateLimitResult(allowed, maxRequests, Math.max(0, maxRequests - weightedCount),
                resetMillis);
    }


    /**
     * Reads the window without consuming a request.
     *
     * <p>Runs the same script with a cost of 0 rather than reading the keys separately: three
     * individual GETs are not atomic and can observe a window mid-slide.
     */
    public RateLimitResult getStatus(String userId, int maxRequests, int windowSeconds) {
        String prefix = RateLimitKeys.slidingWindowPrefix(userId);

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                script,
                Collections.singletonList(prefix),
                String.valueOf(maxRequests),
                String.valueOf(windowSeconds),
                "0");

        if (result == null) {
            throw new RateLimitScriptException(ALGORITHM, prefix);
        }

        boolean allowed = result.get(0) == 1L;
        int weightedCount = result.get(1).intValue();
        long resetMillis = result.get(2);

        return new RateLimitResult(allowed, maxRequests, Math.max(0, maxRequests - weightedCount),
                resetMillis);
    }

    private String luaScript() {
        return """
            -- KEYS[1] is the per-user prefix carrying the hash tag; the three keys below derive
            -- from it and therefore share a cluster slot.
            local key_prefix = KEYS[1]
            local max_requests = tonumber(ARGV[1])
            local window_size_seconds = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])

            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

            local window_size_ms = window_size_seconds * 1000

            local current_key = key_prefix .. ':current'
            local previous_key = key_prefix .. ':previous'
            local timestamp_key = key_prefix .. ':timestamp'

            local window_start = redis.call('GET', timestamp_key)

            if window_start == false then
                window_start = now
                redis.call('SET', timestamp_key, now)
                redis.call('SET', current_key, 0)
                redis.call('SET', previous_key, 0)
            else
                window_start = tonumber(window_start)
            end

            local elapsed = now - window_start

            if elapsed >= window_size_ms then
                local current_count = tonumber(redis.call('GET', current_key) or 0)

                -- More than one whole window may have passed while the user was idle; in that
                -- case the previous window has also aged out entirely.
                if elapsed >= window_size_ms * 2 then
                    redis.call('SET', previous_key, 0)
                else
                    redis.call('SET', previous_key, current_count)
                end

                redis.call('SET', current_key, 0)
                -- Align to the window boundary rather than to now, so windows do not drift
                -- forward by the gap between requests.
                window_start = window_start + math.floor(elapsed / window_size_ms) * window_size_ms
                redis.call('SET', timestamp_key, window_start)
                elapsed = now - window_start
            end

            local current_count = tonumber(redis.call('GET', current_key) or 0)
            local previous_count = tonumber(redis.call('GET', previous_key) or 0)

            local weight = math.max(0, (window_size_ms - elapsed)) / window_size_ms
            local weighted_count = math.floor(previous_count * weight + current_count)

            -- A cost of 0 is a read-only probe: it reports whether the NEXT request would be
            -- allowed, so it tests room for one request without consuming any.
            local probe = cost
            if probe < 1 then
                probe = 1
            end

            local allowed = 0
            if weighted_count + probe <= max_requests then
                if cost > 0 then
                    redis.call('INCRBY', current_key, cost)
                    weighted_count = weighted_count + cost
                end
                allowed = 1
            end

            local reset_ms = window_size_ms - elapsed

            local ttl = window_size_seconds * 2
            redis.call('EXPIRE', current_key, ttl)
            redis.call('EXPIRE', previous_key, ttl)
            redis.call('EXPIRE', timestamp_key, ttl)

            return {allowed, weighted_count, reset_ms}
            """;
    }
}
