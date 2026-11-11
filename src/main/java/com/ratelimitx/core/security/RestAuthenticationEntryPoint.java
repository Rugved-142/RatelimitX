package com.ratelimitx.core.security;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers an unauthenticated request with 401 and a JSON body.
 *
 * <p>Without an explicit entry point Spring Security falls back to
 * {@code Http403ForbiddenEntryPoint} for a stateless API, which reports a missing or unusable
 * token as 403. That conflates "you did not authenticate" with "you are not allowed", and clients
 * cannot tell from it that retrying with a token would help.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(
                "{\"timestamp\":\"" + Instant.now() + "\",\"status\":401,"
                        + "\"error\":\"Unauthorized\","
                        + "\"message\":\"Authentication required\"}");
    }
}
