package com.ratelimitx.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;
import com.ratelimitx.core.support.AbstractIntegrationTest;

/**
 * {@code POST /admin/limit} has to change the limit that is actually enforced.
 *
 * <p>It previously wrote the value into a Redis hash that nothing on the request path read, so it
 * reported success and changed nothing. These tests check the effect on {@code /api/data} rather
 * than the endpoint's own response, because the response was never the part that was wrong.
 */
@AutoConfigureMockMvc
class AdminUserLimitIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;

    private String createUser(Role role, int limit) {
        String username = uniqueUser("lim").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setRateLimit(limit);
        userRepository.save(user);
        return username;
    }

    private String tokenFor(String username, Role role, int limit) {
        return jwtUtil.generateToken(username, "ROLE_" + role.name(), limit);
    }

    private String admin() {
        if (adminToken == null) {
            String username = createUser(Role.ADMIN, 10_000);
            adminToken = tokenFor(username, Role.ADMIN, 10_000);
        }
        return adminToken;
    }

    private int countAllowed(String token, int attempts) throws Exception {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            int status = mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
            if (status == 200) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void raisingALimitChangesWhatIsActuallyEnforced() throws Exception {
        String username = createUser(Role.USER, 5);
        String token = tokenFor(username, Role.USER, 5);

        assertEquals(5, countAllowed(token, 8), "sanity: the original limit is enforced");

        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"rateLimit\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimit").value(20));

        // Clear the counters so this measures the new limit, not what is left of the old window.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/admin/reset/" + username)
                        .header("Authorization", "Bearer " + admin()))
                .andExpect(status().isOk());

        assertEquals(20, countAllowed(token, 25),
                "the new limit must be the one the limiter applies, not just the one it reports");
    }

    @Test
    void loweringALimitChangesWhatIsActuallyEnforced() throws Exception {
        String username = createUser(Role.PREMIUM, 100);
        String token = tokenFor(username, Role.PREMIUM, 100);

        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"rateLimit\":3}"))
                .andExpect(status().isOk());

        assertEquals(3, countAllowed(token, 10));
    }

    @Test
    void changingTheRoleMovesTheUserToThatTiersDefault() throws Exception {
        String username = createUser(Role.USER, 10);

        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"role\":\"PREMIUM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PREMIUM"))
                .andExpect(jsonPath("$.rateLimit").value(100));
    }

    @Test
    void anExplicitLimitWinsOverTheRoleDefault() throws Exception {
        String username = createUser(Role.USER, 10);

        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"role\":\"PREMIUM\",\"rateLimit\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PREMIUM"))
                .andExpect(jsonPath("$.rateLimit").value(42));
    }

    @Test
    void theAdminViewReportsTheSameLimitTheLimiterEnforces() throws Exception {
        String username = createUser(Role.USER, 7);

        mockMvc.perform(get("/admin/user/" + username)
                        .header("Authorization", "Bearer " + admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRequests").value(7));
    }

    @Test
    void anUnknownUserIsNotFound() throws Exception {
        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-at-all\",\"rateLimit\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aNonPositiveLimitIsRejected() throws Exception {
        String username = createUser(Role.USER, 10);

        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"rateLimit\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.rateLimit").exists());
    }

    @Test
    void aMissingUsernameIsRejected() throws Exception {
        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rateLimit\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.username").exists());
    }

    @Test
    void aUserCannotChangeTheirOwnLimit() throws Exception {
        String username = createUser(Role.USER, 5);
        String token = tokenFor(username, Role.USER, 5);

        mockMvc.perform(post("/admin/limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"rateLimit\":1000}"))
                .andExpect(status().isForbidden());

        assertEquals(5, countAllowed(token, 8), "the limit must be unchanged");
    }
}
