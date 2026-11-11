package com.ratelimitx.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;

import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.IntegrationTestContainers;

/**
 * A test may hand the next test a Redis that is not answering yet.
 *
 * <p>This reproduces the failure that made CI flaky. {@code RedisOutageIT} pauses the Redis
 * container, and {@code docker unpause} returns when the cgroup is thawed rather than when the
 * server is answering again. With the application's deliberate 200ms Redis timeout, the next
 * test's inherited setup could issue {@code FLUSHALL} into a Redis that had not caught up, time
 * out, and fail in its own {@code @BeforeEach} with an error about something it was not testing.
 *
 * <p>The two tests here run in order. The first leaves Redis paused and arranges for it to come
 * back a few seconds later, which is deliberately longer than the client timeout; the second only
 * has to reach its body, because reaching it means the inherited setup waited rather than timing
 * out. Before the fix in {@link AbstractIntegrationTest}, the second test errored in setup.
 */
@TestMethodOrder(OrderAnnotation.class)
class RedisReadinessIT extends AbstractIntegrationTest {

    /** Comfortably longer than the 200ms client timeout, so the wait is actually exercised. */
    private static final Duration PAUSE_FOR = Duration.ofSeconds(3);

    private static void pauseRedis() {
        DockerClientFactory.instance().client()
                .pauseContainerCmd(IntegrationTestContainers.redisContainerId()).exec();
    }

    private static void unpauseRedis() {
        try {
            DockerClientFactory.instance().client()
                    .unpauseContainerCmd(IntegrationTestContainers.redisContainerId()).exec();
        } catch (RuntimeException e) {
            // Already running, which is the state we wanted.
        }
        // Same reason as in RedisOutageIT: thawed is not the same as usable.
        IntegrationTestContainers.awaitRedisSettled();
    }

    @AfterAll
    static void neverLeaveItPaused() {
        // If the scheduled unpause failed for any reason, every later test class would fail in
        // setup. This class must not be able to do that to the rest of the suite.
        unpauseRedis();
    }

    @Test
    @Order(1)
    void leavesRedisPausedForTheNextTest() {
        pauseRedis();

        Thread resume = new Thread(() -> {
            try {
                Thread.sleep(PAUSE_FOR.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            unpauseRedis();
        }, "redis-readiness-resume");
        resume.setDaemon(true);
        resume.start();

        // Assert the container really is paused, rather than trusting the command. Probing Redis
        // itself to check would be the very timeout this test exists to survive.
        Boolean actuallyPaused = DockerClientFactory.instance().client()
                .inspectContainerCmd(IntegrationTestContainers.redisContainerId())
                .exec().getState().getPaused();
        assertTrue(Boolean.TRUE.equals(actuallyPaused),
                "Redis should be paused, so the next test's setup has something to wait for");
    }

    @Test
    @Order(2)
    void theNextTestStartsCleanlyEvenSo() {
        // Reaching this line is the assertion. The inherited @BeforeEach had to wait for Redis to
        // answer before flushing; without that wait it would have thrown a timeout and this body
        // would never have run.
        redis.opsForValue().set("readiness-probe", "ok");
        assertEquals("ok", redis.opsForValue().get("readiness-probe"),
                "Redis should be usable once setup has waited for it");
    }
}
