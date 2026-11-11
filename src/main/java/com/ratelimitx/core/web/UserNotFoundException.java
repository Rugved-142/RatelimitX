package com.ratelimitx.core.web;

/**
 * Thrown when a request names a user that does not exist. Mapped to 404 by
 * {@link GlobalExceptionHandler}.
 *
 * <p>Distinct from {@code UsernameNotFoundException}, which means authentication failed and maps
 * to 401.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }
}
