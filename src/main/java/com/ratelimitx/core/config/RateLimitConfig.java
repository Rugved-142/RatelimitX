package com.ratelimitx.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitConfig {
    
    private String algorithm = "token-bucket";
    private int bucketCapacity = 10;
    private double refillRate = 1.0;

    public String getAlgorithm() {
        return algorithm;
    }
    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }
    public int getBucketCapacity() {
        return bucketCapacity;
    }
    public void setBucketCapacity(int bucketCapacity) {
        this.bucketCapacity = bucketCapacity;
    }
    public double getRefillRate() {
        return refillRate;
    }
    public void setRefillRate(double refillRate) {
        this.refillRate = refillRate;
    }


}
