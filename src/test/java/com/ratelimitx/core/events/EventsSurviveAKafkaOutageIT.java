package com.ratelimitx.core.events;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.KafkaTestContainer;
import com.ratelimitx.core.support.TestRedisTimeouts;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * What a Kafka outage does to live traffic: nothing except lose analytics.
 *
 * <p>The broker is paused rather than stopped, so sends hang and then time out instead of being
 * refused outright - the harder of the two failures, and the one that would show up as latency if
 * the request thread were waiting on the send.
 *
 * <p>The timeouts are shortened here so the test does not spend a second per lost event. The
 * production values are in application.properties.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.events.enabled=true",
        "ratelimit.analytics.enabled=false",
        "ratelimit.events.topic=ratelimit-events-outage",
        "spring.kafka.producer.properties.delivery.timeout.ms=400",
        "spring.kafka.producer.properties.request.timeout.ms=300",
        "spring.kafka.producer.properties.linger.ms=0",
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class EventsSurviveAKafkaOutageIT extends AbstractIntegrationTest {

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
    private MeterRegistry meterRegistry;

    private boolean paused;

    @AfterEach
    void alwaysUnpause() {
        if (paused) {
            DockerClientFactory.instance().client()
                    .unpauseContainerCmd(KafkaTestContainer.containerId()).exec();
            paused = false;
        }
    }

    private void pauseKafka() {
        DockerClientFactory.instance().client()
                .pauseContainerCmd(KafkaTestContainer.containerId()).exec();
        paused = true;
    }

    private double count(String meter) {
        return meterRegistry.get(meter).counter().count();
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

    @Test
    void requestsAreUnaffectedAndTheLossIsCounted() throws Exception {
        String token = token();

        // Sanity: the feed works before the outage.
        mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertTrue(count("ratelimit.events.published") > 0,
                        "the feed should be publishing before the broker is paused"));

        double publishedBefore = count("ratelimit.events.published");

        pauseKafka();

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            statuses.add(mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus());
        }

        assertTrue(statuses.stream().allMatch(code -> code == 200),
                "a Kafka outage must not change what /api/data answers, got " + statuses);

        // The events issued during the outage are accounted for as lost, not silently forgotten.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            double lost = count("ratelimit.events.failed") + count("ratelimit.events.dropped");
            assertTrue(lost > 0, "events lost during the outage should be counted, got " + lost);
        });

        assertEquals(publishedBefore, count("ratelimit.events.published"),
                "nothing can be reported as published while the broker is paused");
    }
}
