package com.ratelimitx.core.events;

import static org.awaitility.Awaitility.await;
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
 * Back-pressure: when the queue is full the event is dropped, not waited on.
 *
 * <p>A capacity of one is not a realistic setting - it is the smallest queue that still has a
 * "full" state, which makes the drop deterministic rather than a matter of winning a race against
 * the sending thread. The broker is paused so the one slot stays occupied.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimit.events.enabled=true",
        "ratelimit.analytics.enabled=false",
        "ratelimit.events.topic=ratelimit-events-full",
        "ratelimit.events.queue-capacity=1",
        "spring.kafka.producer.properties.delivery.timeout.ms=400",
        "spring.kafka.producer.properties.request.timeout.ms=300",
        "spring.kafka.producer.properties.linger.ms=0",
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class EventsQueueFullIT extends AbstractIntegrationTest {

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

    @Test
    void aFullQueueDropsEventsAndCountsThemWhileRequestsStillSucceed() throws Exception {
        String username = uniqueUser("full").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setRateLimit(200);
        userRepository.save(user);
        String token = jwtUtil.generateToken(username, "ROLE_USER", 200);

        DockerClientFactory.instance().client()
                .pauseContainerCmd(KafkaTestContainer.containerId()).exec();
        paused = true;

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            statuses.add(mockMvc.perform(get("/api/data").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus());
        }

        assertTrue(statuses.stream().allMatch(code -> code == 200),
                "the request path must not notice a full queue, got " + statuses);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertTrue(meterRegistry.get("ratelimit.events.dropped").counter().count() > 0,
                        "events that did not fit in the queue should be counted as dropped"));
    }
}
