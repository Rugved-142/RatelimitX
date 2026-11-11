package com.ratelimitx.core.config;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * JWT signing configuration.
 *
 * <p>There is deliberately no default for {@code jwt.secret}. The property is bound from the
 * {@code JWT_SECRET} environment variable (Spring's relaxed binding maps {@code JWT_SECRET} to
 * {@code jwt.secret}) and the application refuses to start if it is absent or too short, so a
 * deployment can never fall back to a secret that is public in this repository's history.
 *
 * <p>{@code expiration} is in <strong>milliseconds</strong>. The default of 86400000 is 24 hours.
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * Minimum secret length in bytes. HMAC-SHA256 keys shorter than the 256-bit digest size are
     * rejected outright by jjwt, and a short secret is brute-forceable regardless.
     */
    public static final int MIN_SECRET_BYTES = 32;

    private String secret;

    /** Token lifetime in milliseconds. */
    private long expiration = 86_400_000L;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. RateLimitX will not start without it. "
                            + "Generate one with: openssl rand -base64 48");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short: " + bytes + " bytes, minimum is " + MIN_SECRET_BYTES
                            + " bytes for HMAC-SHA256. Generate one with: openssl rand -base64 48");
        }
        if (expiration <= 0) {
            throw new IllegalStateException(
                    "jwt.expiration must be a positive number of milliseconds, got " + expiration);
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    /** @return token lifetime in milliseconds. */
    public long getExpiration() {
        return expiration;
    }

    /** @param expiration token lifetime in milliseconds. */
    public void setExpiration(long expiration) {
        this.expiration = expiration;
    }
}
