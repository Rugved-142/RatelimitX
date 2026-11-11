package com.ratelimitx.core.circuitbreaker;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ratelimitx.core.model.RateLimitResult;

/**
 * In-memory fixed window limiter used while the circuit is open.
 *
 * <p><strong>These limits are per application instance.</strong> Nothing is shared, so N instances
 * running the fallback simultaneously will between them admit up to N x limit requests per window.
 * That is the deliberate trade: during a Redis outage the service keeps answering with an
 * approximate limit instead of failing. Size the failure threshold and the instance count with
 * that multiplier in mind, and do not read fallback numbers as if they were the real limit.
 *
 * <p>Window state is a single immutable value behind one {@link AtomicReference}, swapped with
 * compare-and-set. The previous version read the count and then incremented it as two separate
 * operations, so two threads could both observe a count below the limit and both increment past
 * it; the window rollover had the same problem, letting two threads each reset the counter and
 * lose the other's requests.
 */
@Component
public class LocalRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(LocalRateLimiter.class);

    private final ConcurrentHashMap<String, AtomicReference<Window>> counters =
            new ConcurrentHashMap<>();

    private final Clock clock;

    /** Window length in milliseconds. Matches the Redis limiters' default window. */
    @Value("${ratelimit.fallback.window-size-ms:60000}")
    private long windowSizeMs = 60_000L;

    /** Requests per window when no caller limit is supplied. */
    @Value("${ratelimit.fallback.default-max-requests:10}")
    private int defaultMaxRequests = 10;

    /**
     * How long a counter may sit untouched before eviction. Without this the map grows with every
     * distinct user seen during an outage and never shrinks, because nothing removes an entry once
     * its window has passed.
     */
    @Value("${ratelimit.fallback.eviction-after-ms:300000}")
    private long evictionAfterMs = 300_000L;

    public LocalRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** One window's state: when it started and how many requests it has admitted. */
    private record Window(long start, int count) {
    }

    /** Checks against the default limit. */
    public RateLimitResult checkRateLimit(String userId) {
        return isAllowed(userId, defaultMaxRequests);
    }

    /**
     * Atomically admits or denies one request.
     *
     * @param maxRequests requests allowed per window <strong>on this instance</strong>
     */
    public RateLimitResult isAllowed(String userId, int maxRequests) {
        AtomicReference<Window> ref = counters.computeIfAbsent(userId,
                k -> new AtomicReference<>(new Window(alignedStart(clock.millis()), 0)));

        while (true) {
            Window observed = ref.get();
            long now = clock.millis();

            // Roll the window forward as part of the same compare-and-set that records the
            // request, so a rollover cannot drop a concurrent increment.
            Window base = (now - observed.start() >= windowSizeMs)
                    ? new Window(alignedStart(now), 0)
                    : observed;

            long resetTime = base.start() + windowSizeMs - now;
            if (resetTime < 0) {
                resetTime = 0;
            }

            if (base.count() >= maxRequests) {
                if (base == observed || ref.compareAndSet(observed, base)) {
                    return new RateLimitResult(false, maxRequests, 0, resetTime);
                }
                continue;
            }

            Window updated = new Window(base.start(), base.count() + 1);
            if (ref.compareAndSet(observed, updated)) {
                return new RateLimitResult(true, maxRequests,
                        Math.max(0, maxRequests - updated.count()), resetTime);
            }
            // Another thread won; re-read and try again.
        }
    }

    /** Aligns a window to a fixed boundary so windows do not drift with request timing. */
    private long alignedStart(long now) {
        return now - (now % windowSizeMs);
    }

    /**
     * Drops counters whose window ended more than {@code evictionAfterMs} ago.
     *
     * <p>Removal is conditional on the entry still being stale, so a user who becomes active
     * between the check and the removal is not silently reset to an empty window.
     */
    @Scheduled(fixedDelayString = "${ratelimit.fallback.eviction-interval-ms:60000}")
    public void evictStaleCounters() {
        long cutoff = clock.millis() - evictionAfterMs;
        int before = counters.size();

        counters.entrySet().removeIf(entry -> {
            Window window = entry.getValue().get();
            return window.start() + windowSizeMs < cutoff;
        });

        int evicted = before - counters.size();
        if (evicted > 0) {
            logger.debug("Evicted {} stale local rate limit counters, {} remain", evicted,
                    counters.size());
        }
    }

    public void clear() {
        counters.clear();
    }

    /** @return how many users this instance is currently tracking in the fallback. */
    public int getActiveUsers() {
        return counters.size();
    }

    /** Current window state, for tests and diagnostics. */
    Map<String, Integer> snapshot() {
        return counters.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get().count()));
    }
}
