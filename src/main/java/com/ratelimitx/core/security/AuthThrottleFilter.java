package com.ratelimitx.core.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ratelimitx.core.config.AuthThrottleProperties;
import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.service.ResilientRateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Throttles the unauthenticated authentication endpoints per client IP.
 *
 * <p>{@code POST /auth/login} and {@code POST /auth/register} are the only endpoints reachable
 * without a token, which makes them the natural target for credential stuffing and for filling the
 * users table. Each is counted separately, against the same Redis-backed limiter the API uses, so
 * the limit holds across application instances and the circuit breaker's local fallback applies
 * when Redis is down.
 *
 * <p>This filter is registered explicitly in {@link SecurityConfig} rather than being a
 * {@code @Component}, which would make Boot register it a second time as a plain servlet filter.
 *
 * <p>Known limitation until phase 3: the token-bucket algorithm ignores the caller-supplied limit,
 * so these limits are only enforced exactly under the fixed-window and sliding-window algorithms.
 */
public class AuthThrottleFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuthThrottleFilter.class);

    private static final String LOGIN_PATH = "/auth/login";
    private static final String REGISTER_PATH = "/auth/register";

    private final ResilientRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuthThrottleProperties properties;

    public AuthThrottleFilter(ResilientRateLimiter rateLimiter, ClientIpResolver clientIpResolver,
            AuthThrottleProperties properties) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getServletPath();
        int limit;
        String bucket;
        if (LOGIN_PATH.equals(path)) {
            limit = properties.getLoginLimitPerMinute();
            bucket = "login";
        } else if (REGISTER_PATH.equals(path)) {
            limit = properties.getRegisterLimitPerMinute();
            bucket = "register";
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);
        RateLimitResult result = rateLimiter.checkRateLimit("auth:" + bucket + ":" + clientIp, limit);

        if (result.isAllowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = retryAfterSeconds(result);
        logger.warn("Throttled {} from {} ({} per minute)", path, clientIp, limit);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Too many authentication attempts. Retry in "
                        + retryAfterSeconds + " seconds.\","
                        + "\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }

    /**
     * Converts the result's reset time to a {@code Retry-After} value in seconds, rounding up and
     * never returning less than 1 — a denied response that says "retry after 0 seconds" invites an
     * immediate retry.
     *
     * <p>Assumes {@code resetTime} is milliseconds. Phase 3 makes that true for every algorithm;
     * the fixed window currently reports seconds, which this clamps to the 1 second minimum.
     */
    private long retryAfterSeconds(RateLimitResult result) {
        long resetMillis = result.getResetTime();
        if (resetMillis <= 0) {
            return 1L;
        }
        return Math.max(1L, (resetMillis + 999L) / 1000L);
    }
}
