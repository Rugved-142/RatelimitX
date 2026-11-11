package com.ratelimitx.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-client-IP throttling of the unauthenticated authentication endpoints.
 *
 * <p>Both limits are requests per minute per client IP, counted separately for each endpoint.
 */
@Component
@ConfigurationProperties(prefix = "ratelimit.auth")
public class AuthThrottleProperties {

    /** Requests per minute per client IP allowed against {@code POST /auth/login}. */
    private int loginLimitPerMinute = 5;

    /** Requests per minute per client IP allowed against {@code POST /auth/register}. */
    private int registerLimitPerMinute = 5;

    /**
     * Whether to read the client IP from {@code X-Forwarded-For}.
     *
     * <p>Off by default: the header is attacker-controlled unless a trusted proxy overwrites it,
     * and trusting it blindly lets a caller bypass the throttle entirely by varying the header.
     * Enable it only when every request reaches this service through a proxy that sets it.
     */
    private boolean trustForwardedFor = false;

    public int getLoginLimitPerMinute() {
        return loginLimitPerMinute;
    }

    public void setLoginLimitPerMinute(int loginLimitPerMinute) {
        this.loginLimitPerMinute = loginLimitPerMinute;
    }

    public int getRegisterLimitPerMinute() {
        return registerLimitPerMinute;
    }

    public void setRegisterLimitPerMinute(int registerLimitPerMinute) {
        this.registerLimitPerMinute = registerLimitPerMinute;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }
}
