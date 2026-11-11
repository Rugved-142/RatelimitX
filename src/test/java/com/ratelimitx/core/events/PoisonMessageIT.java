package com.ratelimitx.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.KafkaTestContainer;
import com.ratelimitx.core.support.TestRedisTimeouts;

/**
 * A record the consumer cannot read is parked, not retried forever.
 *
 * <p>Without this the feed would stop at the first malformed payload: the record stays at the head
 * of the partition, fails, is retried, fails again, and nothing behind it is ever processed.
 *
 * <p>The malformed record is written with a plain byte array producer, because the application's
 * own producer could not create one - which is the point. In production such a record comes from
 * something other than this application writing to the topic.
 */
@TestPropertySource(properties = {
        "ratelimit.events.enabled=true",
        "ratelimit.analytics.enabled=true",
        "ratelimit.events.topic=ratelimit-events-poison",
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class PoisonMessageIT extends AbstractIntegrationTest {

    private static final String TOPIC = "ratelimit-events-poison";
    private static final String DEAD_LETTER_TOPIC = TOPIC + EventsConfig.DEAD_LETTER_SUFFIX;
    private static final byte[] NOT_JSON = "{\"this\":is not valid json".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry);
    }

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private HourlyAggregateRepository aggregates;

    @BeforeEach
    void clearAnalytics() {
        // Postgres is shared with the other analytics tests, so "nothing was counted" is only
        // meaningful about rows this test could have produced.
        aggregates.deleteAll();
        processedEvents.deleteAll();
    }

    private static void publishRaw(String topic, byte[] payload) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(config)) {
            producer.send(new ProducerRecord<>(topic, "poison".getBytes(StandardCharsets.UTF_8),
                    payload));
            producer.flush();
        }
    }

    private static List<ConsumerRecord<byte[], byte[]>> readDeadLetterTopic() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(2));
                if (!records.isEmpty()) {
                    return List.of(records.records(DEAD_LETTER_TOPIC).iterator().next());
                }
            }
            return List.of();
        }
    }

    @Test
    void aRecordThatCannotBeDeserializedEndsUpOnTheDeadLetterTopic() {
        publishRaw(TOPIC, NOT_JSON);

        List<ConsumerRecord<byte[], byte[]>> parked = readDeadLetterTopic();

        assertFalse(parked.isEmpty(),
                "the unreadable record should have been published to " + DEAD_LETTER_TOPIC);
        assertEquals(new String(NOT_JSON, StandardCharsets.UTF_8),
                new String(parked.get(0).value(), StandardCharsets.UTF_8),
                "the original bytes should be preserved, so the record can be inspected");
        assertTrue(processedEvents.count() == 0 && aggregates.count() == 0,
                "a record that was never readable must not have been counted");
    }
}
