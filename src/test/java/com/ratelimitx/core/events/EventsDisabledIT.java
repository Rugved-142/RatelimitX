package com.ratelimitx.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.ratelimitx.core.entity.Role;
import com.ratelimitx.core.entity.User;
import com.ratelimitx.core.repository.UserRepository;
import com.ratelimitx.core.security.JwtUtil;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.TestRedisTimeouts;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The application with the feed off, which is the default and the normal deployment.
 *
 * <p>There is deliberately no Kafka container in this test. If anything here needed a broker the
 * test would fail, which is the strongest available statement that a deployment without Kafka is
 * unaffected by this feature.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class EventsDisabledIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RateLimitEventPublisher publisher;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void theApplicationStartsAndServesRequestsWithNoBrokerAnywhere() throws Exception {
        String username = uniqueUser("nokafka").replace("-", "");
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setRateLimit(10);
        userRepository.save(user);

        mockMvc.perform(get("/api/data")
                        .header("Authorization", "Bearer "
                                + jwtUtil.generateToken(username, "ROLE_USER", 10)))
                .andExpect(status().isOk());
    }

    @Test
    void noPublisherThreadIsRunning() {
        // Asked of the publisher rather than by scanning thread names: the test JVM caches the
        // contexts of the other analytics tests, whose publishers legitimately do have a live
        // sending thread, so a JVM-wide scan says nothing about this context.
        assertFalse(publisher.isRunning(),
                "a disabled feed should not have started a sending thread");
    }

    @Test
    void publishingIsANoOp() {
        publisher.publish(new RateLimitEvent(UUID.randomUUID(), Instant.now(), "nobody", "USER",
                "sliding-window", true, 9));

        assertEquals(0, publisher.queueDepth(), "a disabled feed should not queue anything");
        assertEquals(0.0, meterRegistry.get("ratelimit.events.dropped").counter().count(),
                "an event that was never accepted is not a drop either");
        assertEquals(0.0, meterRegistry.get("ratelimit.events.published").counter().count());
    }

    @Test
    void noTopicsAreDeclaredAndNoConsumerIsStarted() {
        assertTrue(context.getBeansOfType(org.apache.kafka.clients.admin.NewTopic.class).isEmpty(),
                "declaring a topic would make KafkaAdmin contact a broker during startup");
        assertFalse(context.containsBean("analyticsConsumer"),
                "the consumer should not exist unless analytics is enabled");
    }
}
