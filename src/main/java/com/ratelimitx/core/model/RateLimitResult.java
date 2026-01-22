package com.ratelimitx.core.model;

public class RateLimitResult {
    private boolean allowed;
    private int limit;
    private int remaining;
    private long resetTime;
    
    // Constructor, getters, setters
    public RateLimitResult(boolean allowed, int limit, int remaining, long resetTime) {
        this.allowed = allowed;
        this.limit = limit;
        this.remaining = remaining;
        this.resetTime = resetTime;
    }
    
    // Getters
    public boolean isAllowed() { return allowed; }
    public int getLimit() { return limit; }
    public int getRemaining() { return remaining; }
    public long getResetTime() { return resetTime; }
}