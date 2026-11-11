package com.ratelimitx.core.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Provides the application's time source and enables scheduled tasks.
 *
 * <p>Components that make time-dependent decisions take this {@link Clock} rather than calling
 * {@code System.currentTimeMillis()}, so their behaviour over time can be tested by substituting a
 * fixed or offset clock instead of sleeping.
 *
 * <p>Note that this is the <em>application</em> clock, used for the circuit breaker and the local
 * fallback. The Redis-backed limiters deliberately read time from Redis instead, so that several
 * instances agree on where a window boundary falls.
 */
@Configuration
@EnableScheduling
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
