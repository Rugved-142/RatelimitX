package com.ratelimitx.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The application must refuse to start without a usable JWT signing key, rather than falling back
 * to a default that is public in this repository's history.
 */
class JwtPropertiesTest {

    private static final String VALID_SECRET =
            "a-signing-key-that-is-comfortably-longer-than-32-bytes";

    @Test
    void rejectsAnAbsentSecret() {
        JwtProperties properties = new JwtProperties();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                properties::validate);
        assertThat(thrown).hasMessageContaining("JWT_SECRET is not set");
        assertThat(thrown).hasMessageContaining("openssl rand");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsABlankSecret(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);

        assertThat(assertThrows(IllegalStateException.class, properties::validate))
                .hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void rejectsASecretShorterThan32Bytes() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a".repeat(31));

        assertThat(assertThrows(IllegalStateException.class, properties::validate))
                .hasMessageContaining("31 bytes")
                .hasMessageContaining("minimum is 32");
    }

    @Test
    void acceptsExactly32Bytes() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a".repeat(JwtProperties.MIN_SECRET_BYTES));

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void measuresBytesRatherThanCharacters() {
        // 16 multi-byte characters are 32 characters' worth of bytes but only 16 characters, so a
        // character-based check would reject a key that is in fact long enough.
        String multiByte = "é".repeat(16);
        assertThat(multiByte.length()).isEqualTo(16);
        assertThat(multiByte.getBytes(StandardCharsets.UTF_8).length).isEqualTo(32);

        JwtProperties properties = new JwtProperties();
        properties.setSecret(multiByte);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void rejectsANonPositiveExpiration() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(VALID_SECRET);
        properties.setExpiration(0);

        assertThat(assertThrows(IllegalStateException.class, properties::validate))
                .hasMessageContaining("jwt.expiration must be a positive");
    }

    // ==================== context refuses to start ====================

    @org.springframework.boot.context.properties.EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfiguration {
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(JwtPropertiesConfiguration.class);
    }

    @Test
    void theApplicationContextFailsWithoutASecret() {
        // The message is on the root cause; Spring wraps it in a BeanCreationException.
        runner().run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is not set"));
    }

    @Test
    void theApplicationContextFailsWithAShortSecret() {
        runner().withPropertyValues("jwt.secret=too-short")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("JWT_SECRET is too short"));
    }

    @Test
    void theApplicationContextStartsWithAUsableSecret() {
        runner().withPropertyValues("jwt.secret=" + VALID_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtProperties.class).getSecret())
                            .isEqualTo(VALID_SECRET);
                });
    }

    @Test
    void theSecretBindsFromTheJwtSecretEnvironmentVariableName() {
        // Relaxed binding maps the JWT_SECRET environment variable onto jwt.secret, and that is
        // the only way the deployed application receives it. This has to go in through a
        // SystemEnvironmentPropertySource: a plain property named JWT_SECRET is not relaxed-mapped,
        // so withPropertyValues alone would not exercise the real path.
        runner().withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                                "test-env", java.util.Map.of("JWT_SECRET", VALID_SECRET))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtProperties.class).getSecret())
                            .isEqualTo(VALID_SECRET);
                });
    }
}
