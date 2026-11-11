package com.ratelimitx.core.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the feed and hands each event to {@link AnalyticsService}.
 *
 * <p>Declared only when {@code ratelimit.analytics.enabled} is true, so a deployment that only
 * publishes never starts a consumer. Deliberately thin: everything that needs a transaction lives
 * in the service, and anything this method throws is routed by the configured error handler.
 */
@Component
@ConditionalOnProperty(prefix = "ratelimit.analytics", name = "enabled", havingValue = "true")
public class AnalyticsConsumer {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsConsumer.class);

    private final AnalyticsService analytics;

    public AnalyticsConsumer(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @KafkaListener(topics = "${ratelimit.events.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onEvent(RateLimitEvent event) {
        boolean counted = analytics.record(event);
        if (!counted) {
            logger.debug("Event {} was already counted; ignoring the redelivery", event.eventId());
        }
    }
}
