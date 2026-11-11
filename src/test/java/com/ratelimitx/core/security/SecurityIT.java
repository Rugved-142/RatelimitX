package com.ratelimitx.core.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.TestRedisTimeouts;
import com.ratelimitx.core.support.IntegrationTestContainers;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * End-to-end authentication and authorization behaviour over HTTP.
 */
@AutoConfigureMockMvc
// Behavioural test: it verifies rate limiting, not timeout handling, so it does not run
// against the 200ms production Redis timeout. See TestRedisTimeouts.
@TestPropertySource(properties = {
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class SecurityIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    private String createUserAndToken(Role role, int limit) {
        String username = uniqueUser("sec").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setRateLimit(limit);
        userRepository.save(user);
        return jwtUtil.generateToken(username, "ROLE_" + role.name(), limit);
    }

    // ==================== no token ====================

    @ParameterizedTest(name = "{0} without a token is 401")
    @ValueSource(strings = {"/api/data", "/api/status", "/auth/me", "/admin/stats",
            "/admin/health", "/admin/circuit", "/admin/algorithm"})
    void protectedEndpointsRejectAnAbsentToken(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void anAuthorizationHeaderWithoutBearerIsRejected() throws Exception {
        mockMvc.perform(get("/api/data").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== bad tokens ====================

    @ParameterizedTest(name = "malformed token: {0}")
    @ValueSource(strings = {
            "not-a-token",
            "a.b.c",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoYWNrZXIifQ.wrongsignature",
            ""
    })
    void malformedOrForgedTokensAreRejected(String token) throws Exception {
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithTheWrongKeyIsRejected() throws Exception {
        // Correctly structured and unexpired, but signed by someone who does not have the secret.
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "an-attacker-key-that-is-also-long-enough-for-hmac-sha256".getBytes());
        String forged = Jwts.builder()
                .subject("attacker")
                .claim("role", "ROLE_ADMIN")
                .claim("rateLimit", 1000)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(attackerKey)
                .compact();

        mockMvc.perform(get("/admin/stats").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String username = uniqueUser("expired").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setRateLimit(10);
        userRepository.save(user);

        SecretKey key = Keys.hmacShaKeyFor(IntegrationTestContainers.TEST_JWT_SECRET.getBytes());
        String expired = Jwts.builder()
                .subject(username)
                .claim("role", "ROLE_USER")
                .claim("rateLimit", 10)
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(key)
                .compact();

        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidlySignedTokenForAUserThatNoLongerExistsIsRejected() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(IntegrationTestContainers.TEST_JWT_SECRET.getBytes());
        String ghost = Jwts.builder()
                .subject("user-that-was-deleted")
                .claim("role", "ROLE_ADMIN")
                .claim("rateLimit", 1000)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();

        mockMvc.perform(get("/admin/stats").header("Authorization", "Bearer " + ghost))
                .andExpect(status().isUnauthorized());
    }

    // ==================== role enforcement ====================

    // The last entry is deliberately a path that does not exist: everything under /admin must
    // answer 403 to a non-admin, not 404, so the absence of an endpoint cannot be probed.
    @ParameterizedTest(name = "USER token cannot reach {0}")
    @ValueSource(strings = {"/admin/stats", "/admin/health", "/admin/circuit",
            "/admin/algorithm", "/admin/user/someone", "/admin/no-such-endpoint"})
    void userRoleCannotReachAdminEndpoints(String path) throws Exception {
        String token = createUserAndToken(Role.USER, 10);

        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void premiumRoleCannotReachAdminEndpoints() throws Exception {
        String token = createUserAndToken(Role.PREMIUM, 100);

        mockMvc.perform(get("/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleCanReachAdminEndpoints() throws Exception {
        String token = createUserAndToken(Role.ADMIN, 1000);

        mockMvc.perform(get("/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/circuit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void userRoleCannotSwitchTheAlgorithm() throws Exception {
        String token = createUserAndToken(Role.USER, 10);

        mockMvc.perform(post("/admin/algorithm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"algorithm\":\"fixed-window\"}"))
                .andExpect(status().isForbidden());
    }

    // ==================== actuator ====================

    @ParameterizedTest(name = "actuator path {0} is not served on the application port")
    @ValueSource(strings = {"/actuator/health", "/actuator/prometheus", "/actuator/env",
            "/actuator/beans", "/actuator/info"})
    void actuatorIsNotReachableOnTheApplicationPort(String path) throws Exception {
        // Actuator is bound to management.server.port, which compose never publishes. Nothing
        // under /actuator should be mapped on the application port at all.
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound());
    }

    // ==================== validation and error shape ====================

    @Test
    void registrationValidatesItsInput() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"email\":\"not-an-email\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields.username").exists())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void aMalformedBodyIsABadRequestNotAServerError() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void badCredentialsReturn401AndDoNotRevealWhetherTheUserExists() throws Exception {
        String username = uniqueUser("creds").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setRateLimit(10);
        userRepository.save(user);

        String wrongPassword = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-here-at-all\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(wrongPassword, noSuchUser,
                "the two responses must be identical, or they disclose which usernames exist");
    }

    @Test
    void anUnknownPathDoesNotRevealItsAbsenceToAnAnonymousCaller() throws Exception {
        // 401 rather than 404 on purpose: the filter chain authenticates before routing, so an
        // anonymous caller cannot probe which paths exist by comparing status codes.
        mockMvc.perform(get("/no/such/path"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownPathIsNotFoundForAnAuthenticatedCallerRatherThanAServerError() throws Exception {
        String token = createUserAndToken(Role.USER, 10);

        mockMvc.perform(get("/no/such/path").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void thereIsNoLegacyMetricsEndpoint() throws Exception {
        String token = createUserAndToken(Role.USER, 10);

        mockMvc.perform(get("/metrics/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
