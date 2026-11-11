package com.ratelimitx.core.service;

/**
 * Thrown when a rate limiting Lua script returns no result.
 *
 * <p>A null result means the call did not complete — the connection dropped, the command timed
 * out, or the script failed. Guessing an answer in that situation is the wrong move in both
 * directions: returning "allowed" silently disables the limit, and returning "denied" rejects
 * legitimate traffic while Redis is merely slow. Throwing lets the circuit breaker see the
 * failure, count it, and switch to the local fallback.
 */
public class RateLimitScriptException extends IllegalStateException {

    public RateLimitScriptException(String algorithm, String key) {
        super("Rate limit script for " + algorithm + " returned no result for key " + key);
    }
}
