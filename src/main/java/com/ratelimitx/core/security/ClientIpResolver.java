package com.ratelimitx.core.security;

import org.springframework.stereotype.Component;

import com.ratelimitx.core.config.AuthThrottleProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP used as the throttling key.
 *
 * <p>{@code X-Forwarded-For} is read only when {@code ratelimit.auth.trust-forwarded-for} is
 * explicitly enabled. Any client can send that header, so trusting it unconditionally would let a
 * caller defeat the throttle by sending a different value on every request.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final AuthThrottleProperties properties;

    public ClientIpResolver(AuthThrottleProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the client IP, or {@code "unknown"} if the container reports none. Never null, so
     *         callers always have a usable throttling key.
     */
    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                // The left-most entry is the originating client; the rest are proxies.
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
