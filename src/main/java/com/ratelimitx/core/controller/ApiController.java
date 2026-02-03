package com.ratelimitx.core.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.service.MetricsService;
import com.ratelimitx.core.service.ResilientRateLimiter;

/**
 * Main API Controller - Now with JWT Authentication!
 * 
 * User ID is extracted from JWT token, not from header.
 * Rate limit is based on user's tier (USER, PREMIUM, ADMIN).
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    
    private final ResilientRateLimiter resilientRateLimiter;
    private final MetricsService metricsService;
    private final UserRepository userRepository;
    
    @Value("${ratelimit.algorithm}")
    private String algorithm;
    
    public ApiController(
            ResilientRateLimiter resilientRateLimiter,
            MetricsService metricsService,
            UserRepository userRepository
    ) {
        this.resilientRateLimiter = resilientRateLimiter;
        this.metricsService = metricsService;
        this.userRepository = userRepository;
    }
    
    @GetMapping("/data")
    public ResponseEntity<String> getData(Authentication authentication) {
        
        long startTime = System.currentTimeMillis();
        
        // Get username from JWT (via Authentication object)
        String userId = authentication.getName();
        
        // Load user to get their rate limit
        User user = userRepository.findByUsername(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Check rate limit with user's custom limit
        RateLimitResult result = resilientRateLimiter.checkRateLimit(userId, user.getRateLimit());
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        // Record metrics (with try-catch in case Redis is down)
        try {
            metricsService.recordRequest(userId, result.isAllowed(), responseTime);
        } catch (Exception e) {
            // Metrics are non-critical, log and continue
        }
        
        // Build response headers
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity
                .status(result.isAllowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
                .header("X-RateLimit-Limit", String.valueOf(user.getRateLimit()))
                .header("X-RateLimit-Remaining", String.valueOf(result.getRemaining()))
                .header("X-RateLimit-Reset", String.valueOf(result.getResetTime()))
                .header("X-Algorithm", resilientRateLimiter.getCurrentMode())
                .header("X-User-Role", user.getRole().name());
        
        if (!result.isAllowed()) {
            responseBuilder.header("Retry-After", String.valueOf(result.getResetTime() / 1000));
            return responseBuilder.body("Rate limit exceeded. Retry after " + result.getResetTime() + "ms");
        }
        
        return responseBuilder.body("Success! Here's your data");
    }
    
    /**
     * Get current user's rate limit status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(Authentication authentication) {
        String userId = authentication.getName();
        
        User user = userRepository.findByUsername(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return ResponseEntity.ok(java.util.Map.of(
                "username", user.getUsername(),
                "role", user.getRole().name(),
                "rateLimit", user.getRateLimit(),
                "algorithm", resilientRateLimiter.getCurrentMode()
        ));
    }
}
