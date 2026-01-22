package com.ratelimitx.core.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;

import jakarta.annotation.PostConstruct;





@Service
public class SlidingWindowService {


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("rateLimitConfig")
    private RateLimitConfig config;

    private DefaultRedisScript<List> slidingWindowScript;

    @PostConstruct
    public void init(){
        slidingWindowScript = new DefaultRedisScript<>();
        slidingWindowScript.setScriptText(getLuaScript());
        slidingWindowScript.setResultType(List.class);
    }

    public RateLimitResult checkRateLimit(String userId){
        return checkRateLimit(userId, config.getMaxRequests(), config.getWindowSizeSeconds());
    }
    public RateLimitResult checkRateLimit(String userId, int maxRequests, int windowSeconds){

        String keyPrefix = "sliding:"+userId;
        long now = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
            slidingWindowScript,
            Arrays.asList(keyPrefix),
            String.valueOf(maxRequests),
            String.valueOf(windowSeconds),
            String.valueOf(now)
        );

        if(result == null){
            return new RateLimitResult(false, windowSeconds, maxRequests, windowSeconds);
        }

        boolean isAllowed= result.get(0) == 1;
        int currentCount = result.get(1).intValue();
        int remaining = Math.max(0, maxRequests - currentCount);
        Long resetTime = result.get(2);

        return new RateLimitResult(isAllowed, maxRequests, remaining, resetTime);
    }

    public RateLimitResult getStatus(String userId){
        return getStatus(userId, config.getMaxRequests(), config.getWindowSizeSeconds());
    }
    public RateLimitResult getStatus(String userId, int maxRequests, int windowSeconds){
        String currentKey = "sliding:"+userId+":current";
        String previousKey = "sliding:" + userId + ":previous";
        String timestampKey = "sliding:" + userId + ":timestamp";

        long now = System.currentTimeMillis();
        long windowSizems = windowSeconds*1000L;

        String currentCountStr = redisTemplate.opsForValue().get(currentKey);
        String previousCountStr = redisTemplate.opsForValue().get(previousKey);
        String windowStartStr = redisTemplate.opsForValue().get(timestampKey);

        int currentCount = currentCountStr != null ? Integer.parseInt(currentCountStr) : 0;
        int previousCount = previousCountStr != null ? Integer.parseInt(previousCountStr) : 0;
        long windowStart = windowStartStr != null ? Long.parseLong(windowStartStr) : now;

        long elapsed = now - windowStart;
        double weight = Math.max(0,(windowSizems-elapsed)) / (double)windowSizems;
        int weightedCount = (int) Math.floor(previousCount*weight + currentCount);

        int remaining = Math.max(0,(maxRequests-weightedCount));
        long resetTime = windowSizems - elapsed;

        boolean allowed = weightedCount < maxRequests;

        return new RateLimitResult(allowed, maxRequests, remaining, resetTime);
    }


    private String getLuaScript() {
        return """
            local key_prefix = KEYS[1]
            local max_requests = tonumber(ARGV[1])
            local window_size_seconds = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local window_size_ms = window_size_seconds * 1000

            -- Keys for current window, previous window, and window start timestamp
            local current_key = key_prefix .. ":current"
            local previous_key = key_prefix .. ":previous"
            local timestamp_key = key_prefix .. ":timestamp"

            -- Get current window start time
            local window_start = redis.call('GET', timestamp_key)

            if window_start == false then
                -- First request ever - initialize
                window_start = now
                redis.call('SET', timestamp_key, now)
                redis.call('SET', current_key, 0)
                redis.call('SET', previous_key, 0)
            else
                window_start = tonumber(window_start)
            end

            -- Check if we need to slide the window
            local elapsed = now - window_start

            if elapsed >= window_size_ms then
                -- Window has passed - slide
                local current_count = redis.call('GET', current_key) or 0
                current_count = tonumber(current_count)

                -- Move current to previous
                redis.call('SET', previous_key, current_count)
                redis.call('SET', current_key, 0)
                redis.call('SET', timestamp_key, now)

                window_start = now
                elapsed = 0
            end

            -- Get counts
            local current_count = tonumber(redis.call('GET', current_key) or 0)
            local previous_count = tonumber(redis.call('GET', previous_key) or 0)

            -- Calculate weighted count using sliding window
            -- Weight = portion of previous window still in our sliding window
            local weight = math.max(0, (window_size_ms - elapsed)) / window_size_ms
            local weighted_count = math.floor(previous_count * weight + current_count)

            local allowed = 0
            local reset_time = window_size_ms - elapsed

            if weighted_count < max_requests then
                -- Increment current window count
                redis.call('INCR', current_key)
                allowed = 1
                weighted_count = weighted_count + 1
            end

            -- Set TTL on all keys (2x window size for safety)
            local ttl = window_size_seconds * 2
            redis.call('EXPIRE', current_key, ttl)
            redis.call('EXPIRE', previous_key, ttl)
            redis.call('EXPIRE', timestamp_key, ttl)

            return {allowed, weighted_count, reset_time}
            """;
    }
}
