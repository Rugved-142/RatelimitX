package com.ratelimitx.core.circuitbreaker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.ratelimitx.core.model.RateLimitResult;











@Component
public class LocalRateLimiter {

    private final ConcurrentHashMap<String, RequestCounter> counters = new ConcurrentHashMap<>();

    private static final int DEFAULT_MAX_REQUESTS = 10;
    private static final int WINDOW_SIZE_MS = 60000;

    public RateLimitResult checkRateLimit(String userId){
        return isAllowed(userId, DEFAULT_MAX_REQUESTS);
    }

    public RateLimitResult isAllowed(String userId, int maxRequests){
        RequestCounter counter  = counters.computeIfAbsent(userId,
        k -> new RequestCounter(System.currentTimeMillis()));

        long now = System.currentTimeMillis();
        if(now - counter.windowStart > WINDOW_SIZE_MS)  counter.reset(now);

        int currentCount = counter.count.get();

        if(currentCount < maxRequests){
            counter.count.incrementAndGet();
            int remaining = maxRequests - currentCount -1;
            long resetTime = WINDOW_SIZE_MS - (now - counter.windowStart);

            return new RateLimitResult(true, maxRequests, remaining, resetTime);
        }else{
            long resetTime = WINDOW_SIZE_MS - (now - counter.windowStart);
            return new RateLimitResult(false, maxRequests, 0, resetTime);
        }
    }

    public void clear() {
        counters.clear();
    }

    public int getActiveUsers() {
        return counters.size();
    }

    private static class RequestCounter {
        volatile long windowStart;
        final AtomicInteger count;

        RequestCounter(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(0);
        }

        void reset(long newWindowStart) {
            this.windowStart = newWindowStart;
            this.count.set(0);
        }
    }
}
