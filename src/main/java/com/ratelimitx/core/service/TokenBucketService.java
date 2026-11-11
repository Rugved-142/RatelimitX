package com.ratelimitx.core.service;

import java.util.Collections;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;

/**
 * Token bucket.
 *
 * <p>Capacity and refill rate are derived from the caller's own limit rather than being fixed:
 * capacity is the tier limit, and the refill rate is {@code limit / 60} tokens per second, so a
 * tier allowed 100 requests a minute can burst to 100 and then sustain 100 a minute. Previously
 * both were hardcoded to 10 and 1.0, which meant a PREMIUM or ADMIN caller was silently limited
 * to the FREE tier's bucket while the response headers advertised their real limit.
 *
 * <p>Elapsed time is measured with Redis's clock, so instances with skewed system clocks cannot
 * refill a bucket at different rates or, worse, refill it twice for the same second.
 */
@Service
public class TokenBucketService {

    private static final String ALGORITHM = "token-bucket";

    /** Seconds per window. The refill rate is the per-window limit spread over this period. */
    private static final double REFILL_PERIOD_SECONDS = 60.0;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitConfig config;
    private final DefaultRedisScript<List> script;

    public TokenBucketService(StringRedisTemplate redisTemplate, RateLimitConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        this.script = new DefaultRedisScript<>(luaScript(), List.class);
    }

    /** Consumes one token using the configured default capacity. */
    public RateLimitResult tryConsume(String userId) {
        return tryConsume(userId, config.getBucketCapacity());
    }

    /**
     * Consumes one token from a bucket sized for the caller's tier.
     *
     * @param limit requests allowed per minute, used as both the bucket capacity and, divided by
     *              60, the refill rate in tokens per second
     */
    public RateLimitResult tryConsume(String userId, int limit) {
        return consume(userId, 1, limit, limit / REFILL_PERIOD_SECONDS);
    }



    private RateLimitResult consume(String userId, int tokens, int capacity, double refillPerSecond) {
        String key = RateLimitKeys.bucket(userId);

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillPerSecond),
                String.valueOf(tokens));

        if (result == null) {
            throw new RateLimitScriptException(ALGORITHM, key);
        }

        boolean allowed = result.get(0) == 1L;
        int remaining = result.get(1).intValue();
        long resetMillis = result.get(2);

        return new RateLimitResult(allowed, capacity, remaining, resetMillis);
    }

    private String luaScript() {
        return """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])

            local time = redis.call('TIME')
            local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1])
            local last_refill = tonumber(bucket[2])

            if tokens == nil or last_refill == nil then
                tokens = capacity
                last_refill = now_ms
            end

            -- Refill for the time that has passed, capped at capacity.
            local elapsed_seconds = (now_ms - last_refill) / 1000.0
            if elapsed_seconds > 0 then
                tokens = math.min(capacity, tokens + elapsed_seconds * refill_rate)
            end

            -- A request of 0 tokens is a read-only probe: it reports whether the NEXT request
            -- would be allowed without taking a token.
            local probe = requested
            if probe < 1 then
                probe = 1
            end

            local allowed = 0
            if tokens >= probe then
                tokens = tokens - requested
                allowed = 1
            end

            -- Milliseconds until one more token accrues; 0 when one is already available.
            local reset_ms = 0
            if tokens < 1 and refill_rate > 0 then
                reset_ms = math.ceil((1 - tokens) / refill_rate * 1000)
            end

            redis.call('HSET', key, 'tokens', tokens, 'last_refill', now_ms)

            -- Keep the bucket only as long as it can still be behind a full refill, so idle users
            -- do not accumulate keys forever.
            local ttl_seconds = 60
            if refill_rate > 0 then
                ttl_seconds = math.ceil(capacity / refill_rate) * 2
            end
            redis.call('EXPIRE', key, ttl_seconds)

            return {allowed, math.floor(tokens), reset_ms}
            """;
    }
}
