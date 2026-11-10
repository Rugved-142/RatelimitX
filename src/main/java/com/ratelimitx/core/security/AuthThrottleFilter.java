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
 * <p>The limit is enforced exactly under every algorithm, including token bucket, which now sizes
 * its bucket from the limit it is given.
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

        String path = requestPath(request);
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

        long retryAfterSeconds = result.retryAfterSeconds();
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
     * The request path, taken from the request URI with any context path removed.
     *
     * <p>Deliberately not {@code getServletPath()}. That returns "" until the container has
     * resolved a servlet mapping, and it also shifts if the application is deployed under a
     * context path or with the dispatcher mapped somewhere other than the root. In any of those
     * cases the path comparison below would stop matching and the throttle would quietly fail
     * open, which is the worst way for a protection like this to break.
     */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }
}
