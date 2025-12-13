package com.ratelimitx.core.service;

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

        if(count == 1)
            redisTemplate.expire(key,60,TimeUnit.SECONDS);

        return count <= maxRequest;
    }
}
