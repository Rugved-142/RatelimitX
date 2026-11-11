package com.ratelimitx.core.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        circuitBreaker.reset();
        localRateLimiter.clear();
    }

    /** A user id unique to this test run, so parallel or repeated runs cannot collide. */
    protected static String uniqueUser(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID();
    }
}
