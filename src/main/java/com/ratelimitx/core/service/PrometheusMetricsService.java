package com.ratelimitx.core.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;














/**
 * Prometheus Metrics Recording Service
 * 
 * Wraps Micrometer/Prometheus metrics and provides methods
 * to record application events and state changes.
 */
@Service
public class PrometheusMetricsService {
    
    private final MeterRegistry meterRegistry;
    private final Counter ratelimitRequestsCounter;
    private final Counter ratelimitDeniedCounter;
    private final Timer ratelimitCheckTimer;
    private final AtomicReference<Integer> activeUsers;
    private final AtomicReference<String> circuitBreakerState;
    
    public PrometheusMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // Initialize counters
        this.ratelimitRequestsCounter = Counter.builder("ratelimit.requests.total")
                .description("Total rate limit requests")
                .register(meterRegistry);
        
        this.ratelimitDeniedCounter = Counter.builder("ratelimit.requests.denied")
                .description("Total denied requests")
                .register(meterRegistry);
        
        // Initialize timer
        this.ratelimitCheckTimer = Timer.builder("ratelimit.check.duration")
                .description("Rate limit check duration")
                .register(meterRegistry);
        
        // Initialize atomic references for gauges
        this.activeUsers = new AtomicReference<>(0);
        this.circuitBreakerState = new AtomicReference<>("CLOSED");
        
        // Register gauges
        io.micrometer.core.instrument.Gauge.builder("app.active.users", activeUsers::get)
                .register(meterRegistry);
        
        io.micrometer.core.instrument.Gauge.builder("app.circuitbreaker.state",
                () -> stateToNumber(circuitBreakerState.get()))
                .register(meterRegistry);
    }
    
    /**
     * Record a rate limit check request
     * 
     * @param userId User ID making the request
     * @param allowed Whether the request was allowed
     * @param algorithm Rate limiting algorithm used
     */
    public void recordRequest(String userId, boolean allowed, String algorithm) {
        try {
            Tags tags = Tags.of(
                    "user_id", userId != null ? userId : "unknown",
                    "allowed", String.valueOf(allowed),
                    "algorithm", algorithm != null ? algorithm : "unknown"
            );
            Counter.builder("ratelimit.requests.total")
                    .tags(tags)
                    .register(meterRegistry)
                    .increment();
            
            // Also increment the general counter
            ratelimitRequestsCounter.increment();
        } catch (Exception e) {
            // Silently ignore errors for non-critical metrics
        }
    }
    
    /**
     * Record a denied rate limit request
     * 
     * @param userId User ID whose request was denied
     */
    public void recordDenied(String userId) {
        try {
            Tags tags = Tags.of("user_id", userId != null ? userId : "unknown");
            Counter.builder("ratelimit.requests.denied")
                    .tags(tags)
                    .register(meterRegistry)
                    .increment();
            
            // Also increment the general counter
            ratelimitDeniedCounter.increment();
        } catch (Exception e) {
            // Silently ignore errors for non-critical metrics
        }
    }
    
    /**
     * Record the duration of a rate limit check
     * 
     * @param durationMs Duration in milliseconds
     */
    public void recordCheckDuration(long durationMs) {
        try {
            ratelimitCheckTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Silently ignore errors for non-critical metrics
        }
    }
    
    /**
     * Record the duration of a rate limit check with algorithm tag
     * 
     * @param durationMs Duration in milliseconds
     * @param algorithm Rate limiting algorithm
     * @param allowed Whether the request was allowed
     */
    public void recordCheckDuration(long durationMs, String algorithm, boolean allowed) {
        try {
            Tags tags = Tags.of(
                    "algorithm", algorithm != null ? algorithm : "unknown",
                    "result", allowed ? "allowed" : "denied"
            );
            Timer.builder("ratelimit.check.duration")
                    .tags(tags)
                    .register(meterRegistry)
                    .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Silently ignore errors for non-critical metrics
        }
    }
    
    /**
     * Set the circuit breaker state
     * 
     * @param state "CLOSED", "OPEN", or "HALF_OPEN"
     */
    public void setCircuitBreakerState(String state) {
        try {
            circuitBreakerState.set(state);
        } catch (Exception e) {
            // Silently ignore errors for non-critical metrics
        }
    }
    
    /**
     * Set the number of active users
     * 
     * @param count Number of active users
     */
    public void setActiveUsers(int count) {
        try {
            activeUsers.set(count);
        } catch (Exception e) {
            // Silently ignore errors for non-critical metrics
        }
    }
    
    /**
     * Increment active users count
     */
    public void incrementActiveUsers() {
        activeUsers.set(activeUsers.get() + 1);
    }
    
    /**
     * Decrement active users count
     */
    public void decrementActiveUsers() {
        int current = activeUsers.get();
        if (current > 0) {
            activeUsers.set(current - 1);
        }
    }
    
    /**
     * Get current circuit breaker state
     */
    public String getCircuitBreakerState() {
        return circuitBreakerState.get();
    }
    
    /**
     * Convert circuit breaker state to numeric value for gauge
     * 0 = CLOSED (healthy), 1 = HALF_OPEN (testing), 2 = OPEN (unhealthy)
     */
    private int stateToNumber(String state) {
        return switch (state) {
            case "CLOSED" -> 0;
            case "HALF_OPEN" -> 1;
            case "OPEN" -> 2;
            default -> -1;
        };
    }
}
