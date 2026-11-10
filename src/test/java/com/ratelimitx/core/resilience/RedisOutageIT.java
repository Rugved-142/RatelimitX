package com.ratelimitx.core.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

import com.ratelimitx.core.circuitbreaker.CircuitBreakerState;
import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.IntegrationTestContainers;

/**
 * What happens to live traffic when Redis stops answering.
 *
 * <p>The container is paused rather than stopped, which reproduces the case that matters: the
 * socket stays open and commands simply never come back, so the client has to time out. Stopping
 * it would give an immediate connection refusal, which is the easier failure to handle.
 *
 * <p>The breaker timeout is shortened to 2 seconds so recovery can be observed without a 30 second
 * wait.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.circuit-breaker.timeout=2s",
        "ratelimit.circuit-breaker.failure-threshold=3"
})
class RedisOutageIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    private boolean paused;

    @AfterEach
    void alwaysUnpause() {
        // A paused container would break every test that runs after this one.
        if (paused) {
            unpauseRedis();
        }
    }

    private void pauseRedis() {
        DockerClientFactory.instance().client()
                .pauseContainerCmd(IntegrationTestContainers.redisContainerId()).exec();
        paused = true;
    }

    private void unpauseRedis() {
        DockerClientFactory.instance().client()
                .unpauseContainerCmd(IntegrationTestContainers.redisContainerId()).exec();
        paused = false;
    }

    private String token() {
        String username = uniqueUser("outage").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setRateLimit(100);
        userRepository.save(user);
        return jwtUtil.generateToken(username, "ROLE_USER", 100);
    }

    private int call(String token) throws Exception {
        return mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void trafficSurvivesARedisOutageAndRecoversAfterwards() throws Exception {
        String token = token();

        assertEquals(200, call(token), "sanity: the service works before the outage");
        assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getState());

        // ---------- outage ----------
        pauseRedis();

        List<Integer> duringOutage = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            duringOutage.add(call(token));
        }

        List<Integer> serverErrors = duringOutage.stream().filter(code -> code >= 500).toList();
        assertTrue(serverErrors.isEmpty(),
                "a Redis outage must not produce 5xx, got " + serverErrors);
        assertTrue(duringOutage.stream().allMatch(code -> code == 200 || code == 429),
                "every response should be a decision, got " + duringOutage);

        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState(),
                "the breaker should be open after repeated Redis failures");
        assertTrue(duringOutage.stream().anyMatch(code -> code == 200),
                "the fallback should still be admitting requests");

        // ---------- recovery ----------
        unpauseRedis();

        // The breaker only reconsiders once its timeout has elapsed.
        Thread.sleep(Duration.ofSeconds(3).toMillis());
        assertEquals(CircuitBreakerState.HALF_OPEN, circuitBreaker.getState(),
                "after the timeout the breaker should be ready to probe");

        assertEquals(200, call(token), "the probe should succeed now that Redis is back");
        assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getState(),
                "a successful probe should close the circuit");

        // And normal service resumes.
        assertEquals(200, call(token));
    }

    @Test
    void theFallbackTakesOverAndIsReportedInTheResponseHeader() throws Exception {
        String token = token();

        pauseRedis();
        for (int i = 0; i < 5; i++) {
            call(token);
        }

        String algorithm = mockMvc.perform(get("/api/data")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getHeader("X-Algorithm");

        assertEquals("local-fallback", algorithm,
                "clients should be able to see that an approximate limiter is in use");
        assertTrue(localRateLimiter.getActiveUsers() > 0,
                "the local limiter should be tracking the user");
    }

    @Test
    void theFallbackStillEnforcesALimit() throws Exception {
        String token = token();

        pauseRedis();
        // Trip the breaker first so every subsequent request is served by the fallback.
        for (int i = 0; i < 5; i++) {
            call(token);
        }
        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());

        localRateLimiter.clear();

        int allowed = 0;
        int denied = 0;
        for (int i = 0; i < 140; i++) {
            if (call(token) == 200) {
                allowed++;
            } else {
                denied++;
            }
        }

        assertTrue(denied > 0, "the fallback must still deny once its own limit is reached");
        assertTrue(allowed <= 100,
                "the fallback should admit no more than the caller's limit on this instance, got "
                        + allowed);
    }

    @Test
    void aRedisOutageDoesNotBreakAuthentication() throws Exception {
        String token = token();
        pauseRedis();

        // Login and register are throttled through the same limiter, so they must degrade too
        // rather than failing outright.
        int status = mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();

        assertNotEquals(500, status, "authentication must not fail because Redis is down");
        assertEquals(200, status);
    }
}
