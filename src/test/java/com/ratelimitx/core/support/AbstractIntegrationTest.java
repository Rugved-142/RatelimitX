package com.ratelimitx.core.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ratelimitx.core.circuitbreaker.CircuitBreaker;
import com.ratelimitx.core.circuitbreaker.LocalRateLimiter;

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

    @BeforeEach
    void resetSharedState() {
        // Redis must be answering before anything is asked of it: a test that paused the
        // container may have only just let it go. One shared gate, used here and after every
        // unpause, rather than a second implementation of the same wait.
        IntegrationTestContainers.awaitRedisSettled();
        try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
        circuitBreaker.reset();
        localRateLimiter.clear();
    }

    /** A user id unique to this test run, so parallel or repeated runs cannot collide. */
    protected static String uniqueUser(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID();
    }
}
