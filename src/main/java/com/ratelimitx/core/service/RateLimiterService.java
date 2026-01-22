package com.ratelimitx.core.service;

import com.ratelimitx.core.model.RateLimitResult;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;




@Service
public class RateLimiterService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;

    public boolean checkRateLimit(String userId, int maxRequest){
        long currentMinute = System.currentTimeMillis()/60000;
        String key="rate:"+userId+":"+currentMinute;

        Long count = redisTemplate.opsForValue().increment(key);

        if(count==null) return true;
        if(count == 1)
            redisTemplate.expire(key,60,TimeUnit.SECONDS);

        return count <= maxRequest;
    }

    public boolean checkCustomRateLimit(String userId){
        String customLimit = (String) redisTemplate.opsForHash().get("user-limits",userId);
        int maxRequest = customLimit !=null ? Integer.parseInt(customLimit) : 10;

        return checkRateLimit(userId, maxRequest);
    }

    public RateLimitResult checkWithInfo(String userId) {
        String customLimit = (String) redisTemplate.opsForHash().get("user-limits", userId);
        int maxRequest = customLimit != null ? Integer.parseInt(customLimit) : 10;

        long currentMinute = System.currentTimeMillis() / 60000;
        String key = "rate:" + userId + ":" + currentMinute;

        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) {
            return new RateLimitResult(true, maxRequest, maxRequest, 0);
        }

        if (count == 1) {
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }

        boolean allowed = count <= maxRequest;
        int remaining = Math.max(0, maxRequest - count.intValue());
        
        // Seconds until window resets
        int secondsIntoMinute = (int)((System.currentTimeMillis() / 1000) % 60);
        long resetTime = 60 - secondsIntoMinute;

        return new RateLimitResult(allowed, maxRequest, remaining, resetTime);
    }
}
