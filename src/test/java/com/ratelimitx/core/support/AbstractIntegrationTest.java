package com.ratelimitx.core.support;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;
import com.ratelimitx.core.circuitbreaker.LocalRateLimiter;
import com.ratelimitx.core.circuitbreaker.RedisFailureClassifier;

/**
 * Base for tests that need a real Redis and PostgreSQL.
 *
 * <p>Each test starts from an empty Redis and a closed circuit breaker, so one test's leftover
 * counters cannot make the next one fail — or, worse, pass.
 *
 * <p>The reset waits for Redis to answer before it flushes. {@code RedisOutageIT} pauses the Redis
 * container, and {@code docker unpause} returns as soon as the cgroup is thawed rather than when
 * the server is answering again. The application's Redis timeout is deliberately 200ms, so on a
 * slow machine the first command after an unpause can exceed it: the next test then failed in its
 * own setup, with a timeout that had nothing to do with what it was testing. That is what happened
 * on CI while passing locally.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected CircuitBreaker circuitBreaker;

    @Autowired
    protected LocalRateLimiter localRateLimiter;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerProperties(registry);
    }

    /**
     * How long to wait for Redis to start answering again before giving up.
     *
     * <p>Generous on purpose. Waiting costs nothing when Redis is already up, because the first
     * attempt succeeds; the only run that spends this time is one that was going to fail anyway,
     * and then the failure says Redis never came back rather than reporting a timeout from
     * whichever test happened to be next.
     */
    private static final Duration REDIS_READY_TIMEOUT = Duration.ofSeconds(30);

    @BeforeEach
    void resetSharedState() {
        awaitRedisResponsive();
        try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        circuitBreaker.reset();
        localRateLimiter.clear();
    }

    /**
     * Blocks until Redis answers a PING, or fails the test if it never does.
     *
     * <p>Only Redis connectivity and timeout failures are retried, using the same classifier the
     * circuit breaker uses in production: anything else is a real problem and should surface
     * immediately rather than after a 30 second wait.
     */
    private void awaitRedisResponsive() {
        long deadline = System.nanoTime() + REDIS_READY_TIMEOUT.toNanos();
        RuntimeException lastFailure = null;

        while (System.nanoTime() < deadline) {
            try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
                connection.ping();
                return;
            } catch (RuntimeException e) {
                if (!RedisFailureClassifier.isRedisFailure(e)) {
                    throw e;
                }
                lastFailure = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for Redis", e);
            }
        }

        throw new IllegalStateException(
                "Redis did not answer within " + REDIS_READY_TIMEOUT
                        + "; a test that pauses the container may have left it paused",
                lastFailure);
    }

    /** A user id unique to this test run, so parallel or repeated runs cannot collide. */
    protected static String uniqueUser(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID();
    }
}
