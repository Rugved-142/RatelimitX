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

import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.service.TokenBucketService;





@RestController
@RequestMapping("/admin")
@CrossOrigin(origins="*")
public class AdminController {

    @Autowired
    private StringRedisTemplate redis;
    @Autowired TokenBucketService tokenBucketService;

    private static final long START_TIME = System.currentTimeMillis();

    @GetMapping("/stats")
    public Map<String,Object> getStats(){
        
        Map<String, Object> stats = new HashMap<>();

        Set<String> rateLimitKeys = redis.keys("rate:*");
        
        Set<String> uniqueUsers = new HashSet<>();
        if (rateLimitKeys != null) {
            for (String key : rateLimitKeys) {
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    uniqueUsers.add(parts[1]);
                }
            }
        }

        stats.put("activeUsers", uniqueUsers.size());
        stats.put("totalActiveKeys", rateLimitKeys != null ? rateLimitKeys.size() : 0);
        
        stats.put("uptimeSeconds", (System.currentTimeMillis() - START_TIME) / 1000);
        stats.put("currentTime", new Date());
        
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        stats.put("memoryUsedMB", usedMemory);
        stats.put("memoryMaxMB", maxMemory);
        
        return stats;
    }

    @GetMapping("/user/{userId}")
    public Map<String, Object> getUserStatus(@PathVariable String userId) {
        Map<String, Object> status = new HashMap<>();
        
        long currentMinute = System.currentTimeMillis() / 60000;
        String key = "rate:" + userId + ":" + currentMinute;
        
        String count = redis.opsForValue().get(key);
        int currentCount = count != null ? Integer.parseInt(count) : 0;
        
        int maxRequests = getUserLimit(userId);
        
        status.put("userId", userId);
        status.put("currentRequests", currentCount);
        status.put("maxRequests", maxRequests);
        status.put("remainingRequests", Math.max(0, maxRequests - currentCount));
        
        int secondsIntoMinute = (int)((System.currentTimeMillis() / 1000) % 60);
        status.put("resetsInSeconds", 60 - secondsIntoMinute);
        
        status.put("isRateLimited", currentCount >= maxRequests);
        
        return status;
    }

    @PostMapping("/limit")
    public Map<String, Object> setUserLimit(@RequestBody Map<String, Object> request){
        String userId = (String) request.get("userId");
        Integer limit = (Integer) request.get("limit");

        redis.opsForHash().put("user-limits",userId,String.valueOf(limit));

        Map<String, Object> response= new HashMap<>();
        response.put("status","success");
        response.put("userId",userId);
        response.put("newLimit",limit);
        return response;
    }

    @DeleteMapping("/reset/{userId}")
    public Map<String, Object> resetUser(@PathVariable String userId){

        Set<String> userKeys = redis.keys("rate:"+userId+":*");
        if(userKeys !=null && !userKeys.isEmpty()){
            redis.delete(userKeys);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Reset user: " + userId);
        return response;
    }

    @GetMapping("/health")
    public Map<String, Object> health(){
        Map<String, Object> health = new HashMap<>();

        try{
            redis.opsForValue().set("health","ok");
            health.put("redis","UP");
            health.put("status","HEALTHY");
        }catch(Exception e){
            health.put("redis","DOWN");
            health.put("status","UNHEALTHY");
        }
        return health;
    }

    @GetMapping("/bucket/{userId}")
    public Map<String, Object> getBucketStatus(@PathVariable String userId){
        RateLimitResult result = tokenBucketService.getBucketStatus(userId);

        Map<String, Object> status = new HashMap<>();
        status.put("userId", userId);
        status.put("algorithm", "token-bucket");
        status.put("tokensRemaining", result.getRemaining());
        status.put("bucketCapacity", result.getLimit());
        status.put("isAllowed", result.isAllowed());

        return status;
    }
    private int getUserLimit(String userId) {
        String customLimit = (String) redis.opsForHash().get("user-limits", userId);
        return customLimit != null ? Integer.parseInt(customLimit) : 10;
    }
}
