package com.ratelimitx.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer configuration.
 *
 * <p>The rate limiting meters themselves are registered by
 * {@code PrometheusMetricsService}. They are not declared here as beans: registering the same
 * meter name both with and without tags produces two separate Prometheus series, so a
 * {@code sum()} over the metric counts every request twice.
 */
@Configuration
public class MetricsConfig {

    /** Tags applied to every meter. */
    @Bean
    public MeterRegistry.Config commonTags(MeterRegistry registry) {
        registry.config().commonTags("service", "ratelimitx");
        return registry.config();
    }
}
