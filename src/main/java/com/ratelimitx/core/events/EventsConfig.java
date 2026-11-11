package com.ratelimitx.core.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The producer side of the analytics feed.
 *
 * <p>The template is declared here rather than taken from Boot's auto-configuration because that
 * one is a {@code KafkaTemplate<?, ?>}, which does not satisfy an injection point asking for
 * {@code KafkaTemplate<String, RateLimitEvent>}. It is built from the same
 * {@code spring.kafka.producer.*} settings, so there is one place to configure the producer.
 *
 * <p>Constructing it contacts nothing: a producer connects on its first send, and when the feed
 * is disabled there is never a first send.
 */
@Configuration
public class EventsConfig {

    /** Suffix Spring Kafka's dead letter publisher appends to the original topic name. */
    static final String DEAD_LETTER_SUFFIX = ".DLT";

    @Bean
    KafkaTemplate<String, RateLimitEvent> rateLimitEventKafkaTemplate(
            KafkaProperties kafkaProperties) {
        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties()));
    }

    /**
     * The feed's topic.
     *
     * <p>Only declared when the feed is enabled: a {@code NewTopic} bean makes Boot's
     * {@code KafkaAdmin} contact the broker during startup, which is pointless - and noisy in the
     * log - on a deployment that is not running Kafka at all.
     *
     * <p>One partition: ordering per user comes free from keying on the user id, and a single
     * consumer is far more capacity than an hourly aggregate needs. Raise it when there is a
     * measurement saying it is needed.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ratelimit.events", name = "enabled", havingValue = "true")
    NewTopic rateLimitEventsTopic(EventsProperties properties) {
        return TopicBuilder.name(properties.getTopic()).partitions(1).replicas(1).build();
    }

    /**
     * Where records that cannot be processed end up.
     *
     * <p>Created alongside the main topic rather than on demand, so a poison message does not turn
     * into a second failure when the consumer tries to park it.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ratelimit.events", name = "enabled", havingValue = "true")
    NewTopic rateLimitEventsDeadLetterTopic(EventsProperties properties) {
        return TopicBuilder.name(properties.getTopic() + DEAD_LETTER_SUFFIX)
                .partitions(1).replicas(1).build();
    }
}
