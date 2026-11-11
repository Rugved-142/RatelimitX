package com.ratelimitx.core.model;

/**
 * The outcome of one rate limit check.
 *
 * <p><strong>{@code resetTime} is always milliseconds</strong>, for every algorithm and for the
 * local fallback. It is the time until the caller gains capacity for one more request: the
 * remaining time in the window for the window-based algorithms, and the time until the next token
 * accrues for the token bucket (0 when tokens are already available).
 *
 * @param allowed whether this request may proceed
 * @param limit the limit that was actually enforced, which is what the response headers report
 * @param remaining requests still available, never negative
 * @param resetTime milliseconds until more capacity exists
 */
public record RateLimitResult(boolean allowed, int limit, int remaining, long resetTime) {

    public boolean isAllowed() {
        return allowed;
    }

    public int getLimit() {
        return limit;
    }

    public int getRemaining() {
        return remaining;
    }

    /** @return milliseconds until more capacity exists. */
    public long getResetTime() {
        return resetTime;
    }

    /**
     * The value for a {@code Retry-After} header, in whole seconds.
     *
     * <p>Rounded up, and never below 1: telling a denied client to retry after 0 seconds invites
     * an immediate retry that is certain to be denied again.
     */
    public long retryAfterSeconds() {
        if (resetTime <= 0) {
            return 1L;
        }
        return Math.max(1L, (resetTime + 999L) / 1000L);
    }
}
