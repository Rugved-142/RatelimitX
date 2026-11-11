package com.ratelimitx.core.events;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What the consumer does with a record it cannot process.
 *
 * <p>A record that fails to deserialize can never succeed, however many times it is retried, so
 * leaving it at the head of the partition would stop the feed permanently. It is published to
 * {@code <topic>.DLT} instead and the consumer moves on.
 */
@Configuration
@ConditionalOnProperty(prefix = "ratelimit.analytics", name = "enabled", havingValue = "true")
public class AnalyticsConfig {

    /** Retries before a record is parked, for failures that might be transient. */
    private static final int RETRIES = 2;

    private static final long RETRY_INTERVAL_MS = 500;

    /**
     * A producer for records that are being parked rather than understood.
     *
     * <p>A poison record arrives half-decoded: the key deserialized to a String, which is why it
     * was never the problem, while the value is the original bytes the value deserializer choked
     * on. Serializing by payload type covers both, where a byte-array serializer rejects the key
     * and the feed's own JSON serializer rejects the value.
     */
    @Bean
    KafkaTemplate<Object, Object> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        DelegatingByTypeSerializer serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer()));
        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(config, serializer, serializer));
    }

    /**
     * Routes failures to the dead letter topic.
     *
     * <p>Spring Boot wires a single {@code CommonErrorHandler} bean into the listener container
     * factory, so this replaces the default handler. Deserialization failures skip the retries:
     * the handler classifies them as fatal, which is right, because the bytes will not change between
     * attempts.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        // The destination is named here rather than left to the default, which Spring Kafka 4
        // spells "<topic>-dlt" - a topic nothing creates, so the parking attempt itself failed.
        // Naming it keeps the topic EventsConfig declares and the topic this writes to the same
        // one, whatever a future version's default happens to be.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(
                        record.topic() + EventsConfig.DEAD_LETTER_SUFFIX, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
    }
}
