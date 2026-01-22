package com.ratelimitx.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitConfig {

    private String algorithm = "sliding-window";

    private int bucketCapacity = 10;
    private double refillRate = 1.0;

    private int maxRequests = 10;
    private int windowSizeSeconds = 60;

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

    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int maxRequests) { this.maxRequests = maxRequests; }

    public int getWindowSizeSeconds() { return windowSizeSeconds; }
    public void setWindowSizeSeconds(int windowSizeSeconds) { this.windowSizeSeconds = windowSizeSeconds; }
}
