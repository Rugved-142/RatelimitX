package com.ratelimitx.core.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.service.RateLimiterService;
import com.ratelimitx.core.service.TokenBucketService;








@RestController
@RequestMapping("/api")
public class ApiController {
    
    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private TokenBucketService tokenBucketService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("rateLimitConfig")
    private RateLimitConfig config;

    @GetMapping("/data")
    public ResponseEntity<String> getData( @RequestHeader(value="X-API-Key", defaultValue="anonymous") String apiKey){

        RateLimitResult result;
    
        if ("token-bucket".equals(config.getAlgorithm())) {
            result = tokenBucketService.tryConsume(apiKey);
        } else {
            result = rateLimiterService.checkWithInfo(apiKey);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(result.getLimit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        headers.set("X-RateLimit-Reset", String.valueOf(result.getResetTime()));
        if(!result.isAllowed()){
            headers.set("Retry-After", String.valueOf(result.getResetTime() / 1000));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers)
                    .body("Rate limit exceeded. Retry after " + result.getResetTime() + "ms");
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body("Success! Here's your data");
    }
}
