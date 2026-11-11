package com.ratelimitx.core.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis and one PostgreSQL container, started once and shared by every integration test.
 *
 * <p>They are started here rather than with {@code @Testcontainers} per class so the whole suite
 * pays for two container starts instead of two per test class. They are never stopped: Ryuk, the
 * Testcontainers reaper, removes them when the JVM exits.
 *
 * <p>Images are pinned to the same versions {@code docker-compose.yml} runs, so the tests exercise
 * the versions that are actually deployed.
 */
public final class IntegrationTestContainers {

    /** Matches the redis image in docker-compose.yml. */
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    /** Matches the postgres image in docker-compose.yml. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    /** Any value of at least 32 bytes; the application refuses to start without one. */
    public static final String TEST_JWT_SECRET =
            "integration-test-signing-key-that-is-long-enough-for-hmac-sha256";

    public static final GenericContainer<?> REDIS;
    public static final PostgreSQLContainer<?> POSTGRES;

    static {
        REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("ratelimitx")
                .withUsername("ratelimitx")
                .withPassword("ratelimitx-test");
        REDIS.start();
        POSTGRES.start();
    }

    private IntegrationTestContainers() {
    }

    /** Points the application at the containers. Call from a {@code @DynamicPropertySource}. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
    }

    /** @return the Redis container's id, for pausing it in the resilience test. */
    public static String redisContainerId() {
        return REDIS.getContainerId();
    }
}
