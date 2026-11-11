package com.ratelimitx.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.ratelimitx.core.support.TestRedisTimeouts;

/**
 * Pins the production Redis timeout, because the integration tests override it.
 *
 * <p>docs/claims.md cites {@code spring.data.redis.timeout} as the evidence for "Redis timeouts are
 * 200ms so an outage trips the breaker quickly", and the behavioural integration tests relax it to
 * {@link TestRedisTimeouts#RELAXED} so that a loaded machine cannot fail them for infrastructure
 * reasons. An override is one careless edit away from becoming the real value, at which point the
 * documented behaviour would quietly stop being true, the circuit breaker would wait seconds
 * instead of milliseconds before falling back, and every test would still pass.
 *
 * <p>The file is read from disk rather than the classpath deliberately. A classpath lookup for
 * {@code /application.properties} can be shadowed by a test resource of the same name, which is
 * precisely the mistake this is here to catch.
 */
class RedisTimeoutConfigTest {

    private static final Path PRODUCTION_PROPERTIES =
            Path.of("src", "main", "resources", "application.properties");

    /** The value the documentation claims and the circuit breaker's behaviour depends on. */
    private static final String PRODUCTION_TIMEOUT = "200ms";

    private Properties productionProperties() throws IOException {
        assertTrue(Files.isRegularFile(PRODUCTION_PROPERTIES),
                "expected " + PRODUCTION_PROPERTIES.toAbsolutePath()
                        + "; this test reads the file directly and must run from the project root");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(PRODUCTION_PROPERTIES)) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void theProductionCommandTimeoutIsStillTwoHundredMilliseconds() throws IOException {
        assertEquals(PRODUCTION_TIMEOUT,
                productionProperties().getProperty("spring.data.redis.timeout"),
                "docs/claims.md cites this value; a test override must never become the real one");
    }

    @Test
    void theProductionConnectTimeoutIsStillTwoHundredMilliseconds() throws IOException {
        assertEquals(PRODUCTION_TIMEOUT,
                productionProperties().getProperty("spring.data.redis.connect-timeout"),
                "an outage should fail fast on connect as well as on command");
    }

    @Test
    void theRelaxedTestTimeoutHasNotLeakedIntoProduction() throws IOException {
        Properties production = productionProperties();
        assertNotEquals(TestRedisTimeouts.RELAXED,
                production.getProperty("spring.data.redis.timeout"),
                "the relaxed test timeout has been copied into production configuration");
        assertNotEquals(TestRedisTimeouts.RELAXED,
                production.getProperty("spring.data.redis.connect-timeout"),
                "the relaxed test timeout has been copied into production configuration");
    }

    @Test
    void theRelaxedTestTimeoutIsActuallyLongerThanProduction() {
        // A relaxed value that is not longer would make the override pointless, and the override
        // is the reason the two tests that keep the production value are worth keeping separate.
        assertTrue(TestRedisTimeouts.RELAXED.endsWith("s")
                        && !TestRedisTimeouts.RELAXED.endsWith("ms"),
                "expected the relaxed timeout to be expressed in whole seconds, got "
                        + TestRedisTimeouts.RELAXED);
    }
}
