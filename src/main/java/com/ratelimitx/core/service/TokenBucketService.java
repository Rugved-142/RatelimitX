package com.ratelimitx.core.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.ratelimitx.core.model.RateLimitResult;

import jakarta.annotation.PostConstruct;









@Service
public class TokenBucketService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<List> tokenBucketScript;


    private static final int DEFAULT_CAPACITY = 10;
    private static final double DEFAULT_REFILL_RATE = 1.0;

    @PostConstruct
    public void init(){
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setScriptText(getLuaScript());
        tokenBucketScript.setResultType(List.class);
    }

    public RateLimitResult tryConsume(String userId){
        return tryConsume(userId,1,DEFAULT_CAPACITY,DEFAULT_REFILL_RATE);
    }
    private RateLimitResult tryConsume(String userId, int tokens, int capacity, double refillRate) {

        String key = "bucket:"+userId;
        long now = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
            tokenBucketScript,
            Arrays.asList(key),
            String.valueOf(capacity),
            String.valueOf(refillRate),
            String.valueOf(now),
            String.valueOf(tokens)
        );

        if(result == null){
            return new RateLimitResult(true, capacity, capacity,0);
        }

        boolean allowed = result.get(0) == 1;
        int remaining = result.get(1).intValue();
        long retryAfterMs = result.get(2);

        return new RateLimitResult(allowed, capacity, remaining, retryAfterMs);
    }

     public RateLimitResult getBucketStatus(String userId) {
        return tryConsume(userId, 0, DEFAULT_CAPACITY, DEFAULT_REFILL_RATE);
    }

    private String getLuaScript() {

        return """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])

            -- Get current bucket state
            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1])
            local last_refill = tonumber(bucket[2])

            -- Initialize bucket if new
            if tokens == nil then
                tokens = capacity
                last_refill = now
            end

            -- Calculate tokens to add based on time elapsed
            local elapsed = (now - last_refill) / 1000.0
            tokens = math.min(capacity, tokens + (elapsed * refill_rate))

            local allowed = 0
            local retry_after = 0

            -- Check if we have enough tokens
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            else
                -- Calculate wait time for enough tokens
                retry_after = math.ceil((requested - tokens) / refill_rate * 1000)
            end

            -- Save bucket state
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
            redis.call('EXPIRE', key, math.ceil(capacity / refill_rate) * 2)

            return {allowed, math.floor(tokens), retry_after}
            """;
    }
}