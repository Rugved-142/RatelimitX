package com.ratelimitx.core.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka broker, shared by the analytics tests.
 *
 * <p>Started by this class's static initialiser, which runs the first time a test actually needs
 * it: the rest of the suite does not pay for a broker it never talks to. Never stopped - Ryuk,
 * the Testcontainers reaper, removes it when the JVM exits.
 *
 * <p>The image matches the one {@code docker-compose.yml} runs, and its version matches the
 * {@code kafka-clients} the Spring Boot BOM manages, so the tests exercise the pairing that is
 * actually deployed.
 *
 * <p>Each test class uses its own topic. The classes share a broker and a consumer group, so
 * without that a class could consume events another class published and count them as its own.
 */
public final class KafkaTestContainer {

    /** Matches the kafka image in docker-compose.yml and kafka-clients 4.1.2 from the BOM. */
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.1.2");

    public static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer(KAFKA_IMAGE);
        KAFKA.start();
    }

    private KafkaTestContainer() {
    }

    /** Points the application at the broker. Call from a {@code @DynamicPropertySource}. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** @return the broker's container id, for pausing it in the outage test. */
    public static String containerId() {
        return KAFKA.getContainerId();
    }
}
