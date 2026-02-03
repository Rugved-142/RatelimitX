package com.ratelimitx.core.entity;

public enum Role {
    
    USER(10),
    PREMIUM(100),
    ADMIN(1000);

    private final int defaultRateLimit;

    public int getDefaultRateLimit() {
        return defaultRateLimit;
    }

    Role (int defaultRateLimit){
        this.defaultRateLimit = defaultRateLimit;
    }

}
