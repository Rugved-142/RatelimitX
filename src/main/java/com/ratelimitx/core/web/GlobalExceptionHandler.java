package com.ratelimitx.core.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.ErrorResponse;

import com.ratelimitx.core.config.UnsupportedAlgorithmException;

import jakarta.validation.ConstraintViolationException;

/**
 * Turns exceptions into JSON instead of letting them surface as an HTML error page or a bare 500.
 *
 * <p>Ordering matters: Spring selects the most specific handler, so the Spring Security exceptions
 * are declared explicitly. Without them the catch-all would swallow an authorization failure and
 * report it as a server error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A request named a user that does not exist. */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UserNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    /** Authentication could not identify the caller. */
    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameNotFound(UsernameNotFoundException ex) {
        logger.debug("Authentication failed: {}", ex.getMessage());
        return body(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password");
    }

    /**
     * Any other authentication failure, including bad credentials. The message is deliberately
     * generic so the response does not reveal whether the account exists.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        logger.debug("Authentication failed: {}", ex.getMessage());
        return body(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password");
    }

    /**
     * Authenticated but not permitted. Also covers {@code AuthorizationDeniedException} from method
     * security, which extends {@link AccessDeniedException}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "Forbidden", "Access denied");
    }

    /** Bean validation on a {@code @Valid @RequestBody} argument. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }
        ResponseEntity<Map<String, Object>> response =
                body(HttpStatus.BAD_REQUEST, "Bad Request", "Validation failed");
        Map<String, Object> payload = new LinkedHashMap<>(response.getBody());
        payload.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    /** Bean validation on method parameters. */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "Bad Request", "Validation failed");
    }

    /** A caller asked for an algorithm that does not exist. */
    @ExceptionHandler(UnsupportedAlgorithmException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedAlgorithm(
            UnsupportedAlgorithmException ex) {
        ResponseEntity<Map<String, Object>> response =
                body(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
        Map<String, Object> payload = new LinkedHashMap<>(response.getBody());
        payload.put("supported", ex.getSupported());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    /** Malformed or missing request body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed request body");
    }

    /** No mapping for the requested path. */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandler(NoHandlerFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "Not Found", "No handler for " + ex.getRequestURL());
    }

    /**
     * Last resort.
     *
     * <p>Many Spring MVC exceptions — an unmapped path, a wrong method, an unsupported media type —
     * implement {@link ErrorResponse} and already carry the right 4xx status. Those are passed
     * through with their own status and logged at debug; turning them into 500 would report a
     * client mistake as a server fault. Anything else is genuinely unexpected, so it is logged
     * with a stack trace and answered with a generic message that leaks no internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            logger.debug("{}: {}", status, ex.getMessage());
            return body(status, status.getReasonPhrase(), status.getReasonPhrase());
        }
        logger.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", status.value());
        payload.put("error", error);
        payload.put("message", message);
        return ResponseEntity.status(status).body(payload);
    }
}
