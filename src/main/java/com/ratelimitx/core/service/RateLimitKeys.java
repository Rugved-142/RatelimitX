package com.ratelimitx.core.service;

/**
 * The single source of truth for Redis key names.
 *
 * <p>Every key embeds the user id in a <strong>hash tag</strong> — the braces in
 * {@code {sliding:alice}:current}. Redis Cluster hashes only the text inside the braces when
 * choosing a slot, so all of one user's keys land on the same node. Without that, the sliding
 * window script, which touches three keys at once, would be rejected by a cluster with
 * CROSSSLOT, and a per-user reset could not delete them in one call.
 *
 * <p>Braces are not wildcards in Redis glob patterns (only {@code * ? [ ] \} are), so the scan
 * patterns below match these keys literally.
 */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    /**
     * Prefix for the fixed window counter. The window number is appended inside the Lua script,
     * because the script reads the clock from Redis rather than from the caller.
     */
    public static String fixedWindowPrefix(String userId) {
        return "{rate:" + userId + "}";
    }

    /** Matches every fixed window key of one user. */
    public static String fixedWindowPattern(String userId) {
        return "{rate:" + userId + "}:*";
    }

    /** Matches the fixed window keys of every user. */
    public static final String FIXED_WINDOW_SCAN = "{rate:*}:*";

    /** The token bucket hash for one user. */
    public static String bucket(String userId) {
        return "{bucket:" + userId + "}";
    }

    /** Matches the token bucket keys of every user. */
    public static final String BUCKET_SCAN = "{bucket:*}";

    /**
     * Prefix for the sliding window keys. The script appends {@code :current}, {@code :previous}
     * and {@code :timestamp}.
     */
    public static String slidingWindowPrefix(String userId) {
        return "{sliding:" + userId + "}";
    }

    /** Matches every sliding window key of one user. */
    public static String slidingWindowPattern(String userId) {
        return "{sliding:" + userId + "}:*";
    }

    /** Matches the sliding window keys of every user. */
    public static final String SLIDING_WINDOW_SCAN = "{sliding:*}:*";

    /**
     * Prefix of the keys the auth endpoint throttle creates. Those keys are keyed by client IP
     * rather than by user, so administrative views that count users exclude them.
     */
    public static final String AUTH_THROTTLE_PREFIX = "auth:";

    /**
     * Extracts the subject from any of the keys above.
     *
     * @return the user id, or null if the key does not have the expected shape
     */
    public static String userIdOf(String key) {
        int open = key.indexOf('{');
        int colon = key.indexOf(':', open);
        int close = key.indexOf('}', colon);
        if (open < 0 || colon < 0 || close < 0) {
            return null;
        }
        return key.substring(colon + 1, close);
    }
}
