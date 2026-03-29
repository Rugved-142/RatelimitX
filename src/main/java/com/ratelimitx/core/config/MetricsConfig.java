package com.ratelimitx.core.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;



/**
 * Prometheus/Micrometer Metrics Configuration
 * 
 * Configures custom metrics for RateLimitX application:
 * - Request counters (total, denied)
 * - Rate limit check timers
 * - Circuit breaker state gauges
 */
@Configuration
@EnableAspectJAutoProxy
public class MetricsConfig {
    
    /**
     * Add common tags to all metrics
     */
    @Bean
    public MeterRegistry.Config commonTags(MeterRegistry registry) {
        registry.config().commonTags("service", "ratelimitx", "version", "1.0");
        return registry.config();
    }
    
    /**
     * Counter for total rate limit requests
     * Tags: user_id, allowed (true/false), algorithm
     */
    @Bean
    public Counter ratelimitRequestsCounter(MeterRegistry registry) {
        return Counter.builder("ratelimit.requests.total")
                .description("Total rate limit check requests")
                .register(registry);
    }
    
    /**
     * Counter for denied rate limit requests
     * Tags: user_id, reason
     */
    @Bean
    public Counter ratelimitDeniedCounter(MeterRegistry registry) {
        return Counter.builder("ratelimit.requests.denied")
                .description("Total denied rate limit requests")
                .register(registry);
    }
    
    /**
     * Timer for rate limit check duration
     * Tags: algorithm, result (allowed/denied)
     */
    @Bean
    public Timer ratelimitCheckTimer(MeterRegistry registry) {
        return Timer.builder("ratelimit.check.duration")
                .description("Time taken to perform rate limit check")
                .register(registry);
    }
    
    /**
     * Enable @Timed annotation support for methods
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
