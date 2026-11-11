package com.ratelimitx.core.events;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.KafkaTestContainer;
import com.ratelimitx.core.support.TestRedisTimeouts;

/**
 * A request becomes an hourly aggregate, and counts exactly once however often it is delivered.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.events.enabled=true",
        "ratelimit.analytics.enabled=true",
        "ratelimit.events.topic=ratelimit-events-e2e",
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class AnalyticsEndToEndIT extends AbstractIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private HourlyAggregateRepository aggregates;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private KafkaTemplate<String, RateLimitEvent> kafkaTemplate;

    @Autowired
    private EventsProperties eventsProperties;

    @BeforeEach
    void clearAnalytics() {
        aggregates.deleteAll();
        processedEvents.deleteAll();
    }

    private String token(Role role, int limit) {
        String username = uniqueUser("events").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setRateLimit(limit);
        userRepository.save(user);
        return jwtUtil.generateToken(username, "ROLE_" + role.name(), limit);
    }

    @Test
    void aRequestTurnsIntoAnHourlyAggregate() throws Exception {
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token(Role.USER, 10)))
                .andExpect(status().isOk());

        await().atMost(PATIENCE).untilAsserted(() -> {
            List<HourlyAggregate> all = aggregates.findAll();
            assertEquals(1, all.size(), "one decision should produce one aggregate row");
            HourlyAggregate aggregate = all.get(0);
            assertEquals(1, aggregate.getAllowed(), "the decision was an admission");
            assertEquals(0, aggregate.getDenied());
            assertEquals("USER", aggregate.getRole());
            assertEquals("sliding-window", aggregate.getAlgorithm(),
                    "the algorithm recorded should be the one that actually decided");
            assertEquals(aggregate.getHour().truncatedTo(ChronoUnit.HOURS), aggregate.getHour(),
                    "aggregates are keyed by the hour");
        });
    }

    @Test
    void aDeniedRequestIsCountedAsDenied() throws Exception {
        String token = token(Role.USER, 1);

        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests());

        await().atMost(PATIENCE).untilAsserted(() -> {
            List<HourlyAggregate> all = aggregates.findAll();
            assertEquals(1, all.size(), "both decisions share one hour, role and algorithm");
            assertEquals(1, all.get(0).getAllowed());
            assertEquals(1, all.get(0).getDenied());
        });
    }

    @Test
    void theSameEventDeliveredTwiceIsCountedOnce() {
        RateLimitEvent event = new RateLimitEvent(UUID.randomUUID(), Instant.now(),
                "repeat-user", "PREMIUM", "sliding-window", true, 99);

        kafkaTemplate.send(eventsProperties.getTopic(), event.userId(), event);
        kafkaTemplate.send(eventsProperties.getTopic(), event.userId(), event);
        kafkaTemplate.flush();

        await().atMost(PATIENCE).untilAsserted(() ->
                assertEquals(1, processedEvents.count(), "the event id should be claimed once"));

        // Hold the assertion for two seconds, so that passing means the second delivery arrived
        // and was ignored rather than simply not having arrived yet.
        await().during(Duration.ofSeconds(2)).atMost(PATIENCE).untilAsserted(() -> {
            List<HourlyAggregate> all = aggregates.findAll();
            assertEquals(1, all.size());
            assertEquals(1, all.get(0).getAllowed(),
                    "a redelivered event must not be counted a second time");
        });
    }

    @Test
    void theAggregatesAreServedToAnAdmin() throws Exception {
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token(Role.USER, 10)))
                .andExpect(status().isOk());

        await().atMost(PATIENCE).untilAsserted(() ->
                assertTrue(aggregates.count() > 0, "there should be something to serve"));

        mockMvc.perform(get("/admin/analytics")
                        .header("Authorization", "Bearer " + token(Role.ADMIN, 1000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].allowed").value(1));
    }
}
