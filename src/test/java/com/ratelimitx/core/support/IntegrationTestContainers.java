package com.ratelimitx.core.support;

import java.io.IOException;
import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container.ExecResult;
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

    // ---------------------------------------------------------------------
    // Waiting for Redis to be usable again after a pause
    // ---------------------------------------------------------------------

    /** Consecutive successful PINGs required before Redis counts as settled. */
    private static final int REQUIRED_CONSECUTIVE_PONGS = 3;

    /** Overall budget. Generous: a run that needs it was going to fail anyway. */
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration PROBE_GAP = Duration.ofMillis(50);

    /**
     * Blocks until Redis answers {@value #REQUIRED_CONSECUTIVE_PONGS} PINGs in a row, or throws
     * after {@link #SETTLE_TIMEOUT}.
     *
     * <p>Call this after unpausing the container, before letting a test continue. {@code docker
     * unpause} returns once the cgroup is thawed, not once the server is answering, and a single
     * successful PING only proves it has started answering - not that whatever queued up during
     * the pause has drained. The application's Redis timeout is deliberately 200ms, so one slow
     * command after an unpause is enough to trip the circuit breaker and make a recovery
     * assertion fail for a reason that has nothing to do with the code under test.
     *
     * <p>The PING runs inside the container, not through the application's Redis client, on
     * purpose: the question is whether the <em>infrastructure</em> is usable again, and the answer
     * should not be subject to the production timeout that is the whole problem.
     */
    public static void awaitRedisSettled() {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        int consecutive = 0;
        String lastFailure = "never attempted";

        while (System.nanoTime() < deadline) {
            String failure = ping();
            if (failure == null) {
                if (++consecutive >= REQUIRED_CONSECUTIVE_PONGS) {
                    return;
                }
            } else {
                // One refused or error PING resets the count: the point is a run of good ones.
                consecutive = 0;
                lastFailure = failure;
            }
            sleep(PROBE_GAP);
        }

        throw new IllegalStateException(String.format(
                "Redis did not answer %d consecutive PINGs within %s (last failure: %s). "
                        + "A test that pauses the container may have left it paused.",
                REQUIRED_CONSECUTIVE_PONGS, SETTLE_TIMEOUT, lastFailure));
    }

    /** @return null if Redis answered PONG, otherwise a description of what happened instead. */
    private static String ping() {
        try {
            ExecResult result = REDIS.execInContainer("redis-cli", "ping");
            if (result.getExitCode() == 0 && result.getStdout().startsWith("PONG")) {
                return null;
            }
            return String.format("redis-cli exited %d (stdout: %s, stderr: %s)",
                    result.getExitCode(), result.getStdout().trim(), result.getStderr().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Redis", e);
        } catch (IOException | RuntimeException e) {
            // A paused container refuses exec outright, which is one of the cases being waited on.
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Redis", e);
        }
    }
}
