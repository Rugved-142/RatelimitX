package com.ratelimitx.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.TestRedisTimeouts;

/**
 * {@code GET /api/data} over HTTP for every tier and every algorithm: the status codes, the
 * rate limit headers and the {@code Retry-After} a client actually receives.
 */
@AutoConfigureMockMvc
// Behavioural test: it verifies rate limiting, not timeout handling, so it does not run
// against the 200ms production Redis timeout. See TestRedisTimeouts.
@TestPropertySource(properties = {
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class ApiRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RateLimitConfig config;

    @AfterEach
    void restoreDefaultAlgorithm() {
        config.setAlgorithmChecked(RateLimitConfig.SLIDING_WINDOW);
    }

    /** Every (algorithm, tier) pair: three algorithms against the three roles and their limits. */
    static Stream<Arguments> algorithmsAndTiers() {
        return RateLimitConfig.sortedNames().stream().flatMap(algorithm -> Stream.of(
                Arguments.of(algorithm, Role.USER, 10),
                Arguments.of(algorithm, Role.PREMIUM, 100),
                Arguments.of(algorithm, Role.ADMIN, 1000)));
    }

    private String tokenFor(Role role, int limit) {
        String username = uniqueUser("api").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setRateLimit(limit);
        userRepository.save(user);
        return jwtUtil.generateToken(username, "ROLE_" + role.name(), limit);
    }

    private MvcResult callApi(String token) throws Exception {
        return mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andReturn();
    }

    @ParameterizedTest(name = "{0} / {1} (limit {2}): 200s then 429 with correct headers")
    @MethodSource("algorithmsAndTiers")
    void eachTierGetsItsLimitThenA429WithCorrectHeaders(String algorithm, Role role, int limit)
            throws Exception {
        config.setAlgorithmChecked(algorithm);
        String token = tokenFor(role, limit);

        // The first response must advertise the tier's real limit, not a default.
        MvcResult first = callApi(token);
        assertEquals(200, first.getResponse().getStatus());
        assertEquals(String.valueOf(limit), first.getResponse().getHeader("X-RateLimit-Limit"),
                algorithm + "/" + role + ": X-RateLimit-Limit must report the enforced limit");
        assertEquals(String.valueOf(limit - 1),
                first.getResponse().getHeader("X-RateLimit-Remaining"));
        assertEquals(role.name(), first.getResponse().getHeader("X-User-Role"));
        assertEquals(algorithm, first.getResponse().getHeader("X-Algorithm"));

        // Exhaust the rest of the allowance.
        for (int i = 1; i < limit; i++) {
            callApi(token);
        }

        MvcResult denied = findFirstDenial(token, limit);
        assertEquals(429, denied.getResponse().getStatus(),
                algorithm + "/" + role + ": the allowance should be exhausted");
        assertEquals(String.valueOf(limit), denied.getResponse().getHeader("X-RateLimit-Limit"));
        assertEquals("0", denied.getResponse().getHeader("X-RateLimit-Remaining"));

        long retryAfter = Long.parseLong(denied.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter >= 1,
                algorithm + "/" + role + ": Retry-After must be at least 1, got " + retryAfter);

        long reset = Long.parseLong(denied.getResponse().getHeader("X-RateLimit-Reset"));
        assertTrue(reset > 0, algorithm + "/" + role + ": reset must be positive, got " + reset);
        assertTrue(reset <= config.getWindowSizeSeconds() * 1000L,
                algorithm + "/" + role + ": reset of " + reset
                        + "ms should be milliseconds inside the window, not seconds");
        assertEquals(Math.max(1L, (reset + 999L) / 1000L), retryAfter,
                algorithm + "/" + role + ": Retry-After must be ceil(reset/1000), floored at 1");
    }

    /**
     * Sends further requests until one is denied.
     *
     * <p>The token bucket refills while a burst is in flight. At the ADMIN limit of 1000 that is
     * about 17 tokens per second, so a burst that takes a second hands back enough tokens that a
     * mere handful of extra attempts cannot exhaust it. The budget is therefore generous but still
     * bounded: requests are issued far faster than tokens accrue, so a denial is reached quickly.
     */
    private MvcResult findFirstDenial(String token, int limit) throws Exception {
        MvcResult last = null;
        for (int i = 0; i < 400; i++) {
            last = callApi(token);
            if (last.getResponse().getStatus() == 429) {
                return last;
            }
        }
        return last;
    }

    @ParameterizedTest(name = "{0} reports itself in X-Algorithm")
    @MethodSource("algorithmsAndTiers")
    void theActiveAlgorithmIsReportedInTheHeader(String algorithm, Role role, int limit)
            throws Exception {
        config.setAlgorithmChecked(algorithm);
        String token = tokenFor(role, limit);

        assertEquals(algorithm, callApi(token).getResponse().getHeader("X-Algorithm"));
    }

    @Test
    void switchingAlgorithmAtRuntimeChangesWhatEnforcesTheNextRequest() throws Exception {
        String token = tokenFor(Role.USER, 10);

        config.setAlgorithmChecked(RateLimitConfig.FIXED_WINDOW);
        assertEquals(RateLimitConfig.FIXED_WINDOW,
                callApi(token).getResponse().getHeader("X-Algorithm"));

        config.setAlgorithmChecked(RateLimitConfig.TOKEN_BUCKET);
        assertEquals(RateLimitConfig.TOKEN_BUCKET,
                callApi(token).getResponse().getHeader("X-Algorithm"));
    }

    @Test
    void aDeniedResponseExplainsItselfInTheBody() throws Exception {
        String token = tokenFor(Role.USER, 10);
        for (int i = 0; i < 10; i++) {
            callApi(token);
        }

        MvcResult denied = findFirstDenial(token, 10);
        assertEquals(429, denied.getResponse().getStatus());
        assertTrue(denied.getResponse().getContentAsString().contains("Rate limit exceeded"),
                "the body should say what happened");
    }

    @Test
    void statusReportsTheCallersOwnTierWithoutConsumingAllowance() throws Exception {
        String token = tokenFor(Role.PREMIUM, 100);

        mockMvc.perform(get("/api/status").header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.role").value("PREMIUM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.rateLimit").value(100));

        // The status endpoint is not rate limited, so the full allowance is still available.
        assertEquals("99", callApi(token).getResponse().getHeader("X-RateLimit-Remaining"));
    }
}
