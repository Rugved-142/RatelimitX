package com.ratelimitx.core.events;

import java.time.Instant;
import java.util.UUID;

/**
 * One rate limit decision, as published to the analytics feed.
 *
 * <p>This is an analytics record, not an audit record: the feed is best-effort and events are
 * dropped when Kafka is unavailable, so the absence of an event does not mean the request did not
 * happen. Nothing on the request path reads it back.
 *
 * @param eventId   identifies this event for deduplication; the consumer keys on it
 * @param timestamp when the decision was made
 * @param userId    the caller the decision applied to
 * @param role      the caller's role at the time
 * @param algorithm the mode that actually decided, which is {@code local-fallback} while the
 *                  circuit breaker is open rather than the configured algorithm
 * @param allowed   whether the request was admitted
 * @param remaining requests left in the caller's current window after this decision
 */
public record RateLimitEvent(
        UUID eventId,
        Instant timestamp,
        String userId,
        String role,
        String algorithm,
        boolean allowed,
        int remaining) {
}
