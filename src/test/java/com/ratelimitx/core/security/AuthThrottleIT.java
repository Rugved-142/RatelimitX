package com.ratelimitx.core.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ratelimitx.core.support.AbstractIntegrationTest;

/**
 * Per-client-IP throttling of the unauthenticated authentication endpoints.
 *
 * <p>The limits are lowered to 3 so a test does not have to send the production default of 5 plus
 * one on every case.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.auth.login-limit-per-minute=3",
        "ratelimit.auth.register-limit-per-minute=3"
})
class AuthThrottleIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private MvcResult login(String ip) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong-password\"}"))
                .andReturn();
    }

    private MvcResult register(String ip, int n) throws Exception {
        return mockMvc.perform(post("/auth/register")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"throttle" + n + "\",\"email\":\"throttle" + n
                                + "@example.com\",\"password\":\"password123\"}"))
                .andReturn();
    }

    @Test
    void loginIsThrottledAfterTheConfiguredNumberOfAttempts() throws Exception {
        String ip = "203.0.113.10";

        for (int i = 1; i <= 3; i++) {
            // Wrong credentials, so 401 is the expected answer while the throttle still permits.
            org.junit.jupiter.api.Assertions.assertEquals(401, login(ip).getResponse().getStatus(),
                    "attempt " + i + " should reach authentication");
        }

        MvcResult throttled = login(ip);
        org.junit.jupiter.api.Assertions.assertEquals(429, throttled.getResponse().getStatus(),
                "the 4th attempt in a minute should be throttled");
    }

    @Test
    void aThrottledLoginCarriesRetryAfterAndAJsonBody() throws Exception {
        String ip = "203.0.113.11";
        for (int i = 0; i < 3; i++) {
            login(ip);
        }

        mockMvc.perform(post("/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfterSeconds").exists());
    }

    @Test
    void retryAfterIsAtLeastOneSecond() throws Exception {
        String ip = "203.0.113.12";
        for (int i = 0; i < 3; i++) {
            login(ip);
        }

        MvcResult throttled = login(ip);
        long retryAfter = Long.parseLong(throttled.getResponse().getHeader("Retry-After"));
        assertTrue(retryAfter >= 1, "Retry-After must never be 0, got " + retryAfter);
    }

    @Test
    void registerIsThrottledSeparatelyFromLogin() throws Exception {
        String ip = "203.0.113.13";

        // Exhaust login.
        for (int i = 0; i < 4; i++) {
            login(ip);
        }
        org.junit.jupiter.api.Assertions.assertEquals(429, login(ip).getResponse().getStatus());

        // Register still has its own allowance from the same IP.
        org.junit.jupiter.api.Assertions.assertEquals(201, register(ip, 1).getResponse().getStatus(),
                "register must not share login's counter");
    }

    @Test
    void throttlingIsPerClientIp() throws Exception {
        String busy = "203.0.113.20";
        for (int i = 0; i < 4; i++) {
            login(busy);
        }
        org.junit.jupiter.api.Assertions.assertEquals(429, login(busy).getResponse().getStatus());

        org.junit.jupiter.api.Assertions.assertEquals(401, login("203.0.113.21").getResponse().getStatus(),
                "a different client IP has its own allowance");
    }

    @Test
    void forwardedForIsIgnoredUnlessExplicitlyTrusted() throws Exception {
        // trust-forwarded-for defaults to false, so a client cannot dodge the throttle by varying
        // the header; all of these must count against the same remote address.
        String ip = "203.0.113.30";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .header("X-Forwarded-For", "10.0.0." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"nobody\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .header("X-Forwarded-For", "10.0.0.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
