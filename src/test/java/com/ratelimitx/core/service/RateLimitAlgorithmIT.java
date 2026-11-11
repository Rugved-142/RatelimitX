package com.ratelimitx.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.ratelimitx.core.config.RateLimitConfig;
import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.support.AbstractIntegrationTest;
import com.ratelimitx.core.support.TestRedisTimeouts;
import com.ratelimitx.core.support.Concurrently;

/**
 * Each algorithm against a real Redis: does it enforce the limit it is given, exactly, including
 * when 200 threads hit one key at once.
 */
// PER_CLASS so the algorithm factory below can be an instance method and use the injected beans.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// Behavioural test: it verifies rate limiting, not timeout handling, so it does not run
// against the 200ms production Redis timeout. See TestRedisTimeouts.
@TestPropertySource(properties = {
        TestRedisTimeouts.COMMAND_TIMEOUT,
        TestRedisTimeouts.CONNECT_TIMEOUT,
})
class RateLimitAlgorithmIT extends AbstractIntegrationTest {

    @Autowired
    private RateLimiterService fixedWindow;

    @Autowired
    private TokenBucketService tokenBucket;

    @Autowired
    private SlidingWindowService slidingWindow;

    @Autowired
    private ResilientRateLimiter resilientRateLimiter;

    @Autowired
    private RateLimitConfig config;

    /**
     * Each algorithm as a (userId, limit) -> result function, so one test body covers all three.
     *
     * <p>The third argument says whether the algorithm replenishes continuously. A token bucket
     * refills while a burst is being sent — at a limit of 1000 that is about 17 tokens a second —
     * so it legitimately admits slightly more than its capacity during a drain. The window
     * algorithms do not replenish until their window ends, so for them the count is exact.
     */
    private Stream<Arguments> algorithms() {
        return Stream.of(
                Arguments.of(RateLimitConfig.FIXED_WINDOW,
                        (BiFunction<String, Integer, RateLimitResult>) fixedWindow::checkWithInfo,
                        false),
                Arguments.of(RateLimitConfig.TOKEN_BUCKET,
                        (BiFunction<String, Integer, RateLimitResult>) tokenBucket::tryConsume,
                        true),
                Arguments.of(RateLimitConfig.SLIDING_WINDOW,
                        (BiFunction<String, Integer, RateLimitResult>) slidingWindow::checkRateLimit,
                        false));
    }

    /**
     * Upper bound on admissions for a burst that took {@code elapsedMs}.
     *
     * <p>For a continuously refilling algorithm this is capacity plus whatever accrued while the
     * burst was in flight, at limit/60 tokens per second, with one token of slack for rounding.
     * This is derived from the measured duration rather than being a loose tolerance.
     */
    private static int maxAdmissions(int limit, boolean refillsContinuously, long elapsedMs) {
        if (!refillsContinuously) {
            return limit;
        }
        double refilled = (limit / 60.0) * (elapsedMs / 1000.0);
        return limit + (int) Math.ceil(refilled) + 1;
    }

    // ==================== limit enforcement ====================

    @ParameterizedTest(name = "{0} admits exactly the limit")
    @MethodSource("algorithms")
    void admitsExactlyTheLimitThenDenies(String name,
            BiFunction<String, Integer, RateLimitResult> limiter, boolean refillsContinuously) {
        String user = uniqueUser("limit");
        int limit = 10;

        for (int i = 1; i <= limit; i++) {
            RateLimitResult result = limiter.apply(user, limit);
            assertTrue(result.isAllowed(), name + ": request " + i + " of " + limit);
            assertEquals(limit, result.getLimit(), name + ": limit must be reported back");
        }

        RateLimitResult denied = limiter.apply(user, limit);
        assertFalse(denied.isAllowed(), name + ": request " + (limit + 1) + " must be denied");
        assertEquals(0, denied.getRemaining(), name + ": nothing remaining once denied");
    }

    @ParameterizedTest(name = "{0} counts each user separately")
    @MethodSource("algorithms")
    void countsEachUserSeparately(String name,
            BiFunction<String, Integer, RateLimitResult> limiter, boolean refillsContinuously) {
        String exhausted = uniqueUser("busy");
        String fresh = uniqueUser("idle");

        for (int i = 0; i < 5; i++) {
            limiter.apply(exhausted, 5);
        }

        assertFalse(limiter.apply(exhausted, 5).isAllowed(), name);
        assertTrue(limiter.apply(fresh, 5).isAllowed(), name + ": a different user is unaffected");
    }

    @ParameterizedTest(name = "{0} applies the tier limit it is given")
    @MethodSource("algorithms")
    void appliesTierLimits(String name, BiFunction<String, Integer, RateLimitResult> limiter,
            boolean refillsContinuously) {
        // The three tiers from Role: USER 10, PREMIUM 100, ADMIN 1000.
        for (int tierLimit : new int[] {10, 100, 1000}) {
            String user = uniqueUser("tier-" + tierLimit);

            long start = System.nanoTime();
            int allowed = 0;
            for (int i = 0; i < tierLimit + 5; i++) {
                if (limiter.apply(user, tierLimit).isAllowed()) {
                    allowed++;
                }
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            int ceiling = maxAdmissions(tierLimit, refillsContinuously, elapsedMs);
            assertTrue(allowed >= tierLimit,
                    name + ": a tier limit of " + tierLimit + " must admit at least that many, got "
                            + allowed);
            assertTrue(allowed <= ceiling,
                    name + ": a tier limit of " + tierLimit + " must admit no more than " + ceiling
                            + " over " + elapsedMs + "ms, got " + allowed);
        }
    }

    @ParameterizedTest(name = "{0} reports reset in milliseconds")
    @MethodSource("algorithms")
    void reportsResetTimeInMilliseconds(String name,
            BiFunction<String, Integer, RateLimitResult> limiter, boolean refillsContinuously) {
        String user = uniqueUser("reset");
        for (int i = 0; i < 10; i++) {
            limiter.apply(user, 10);
        }

        RateLimitResult denied = limiter.apply(user, 10);
        assertFalse(denied.isAllowed());

        long window = config.getWindowSizeSeconds() * 1000L;
        assertTrue(denied.getResetTime() > 0 && denied.getResetTime() <= window,
                name + ": reset of " + denied.getResetTime()
                        + " should be milliseconds within a " + window + "ms window");
        assertTrue(denied.retryAfterSeconds() >= 1, name + ": Retry-After must be at least 1");
    }

    // ==================== concurrency ====================

    @ParameterizedTest(name = "{0} admits exactly the limit under 200 concurrent requests")
    @MethodSource("algorithms")
    void concurrentRequestsOnOneKeyNeverExceedTheLimit(String name,
            BiFunction<String, Integer, RateLimitResult> limiter, boolean refillsContinuously)
            throws Exception {
        String user = uniqueUser("hot");
        int limit = 100;

        long start = System.nanoTime();
        int allowed = Concurrently.countAllowed(200,
                () -> limiter.apply(user, limit).isAllowed());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        int ceiling = maxAdmissions(limit, refillsContinuously, elapsedMs);
        assertTrue(allowed >= limit,
                name + ": 200 concurrent requests must still admit the full limit, got " + allowed);
        assertTrue(allowed <= ceiling,
                name + ": 200 concurrent requests against a limit of " + limit
                        + " must admit no more than " + ceiling + ", got " + allowed);
    }

    @Test
    void theResilientLimiterIsAlsoExactUnderConcurrency() throws Exception {
        for (String algorithm : RateLimitConfig.sortedNames()) {
            config.setAlgorithmChecked(algorithm);
            String user = uniqueUser("resilient-" + algorithm);

            long start = System.nanoTime();
            int allowed = Concurrently.countAllowed(200,
                    () -> resilientRateLimiter.checkRateLimit(user, 100).isAllowed());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            int ceiling = maxAdmissions(100,
                    RateLimitConfig.TOKEN_BUCKET.equals(algorithm), elapsedMs);
            assertTrue(allowed >= 100 && allowed <= ceiling,
                    algorithm + " through ResilientRateLimiter admitted " + allowed
                            + ", expected between 100 and " + ceiling);
        }
        config.setAlgorithmChecked(RateLimitConfig.SLIDING_WINDOW);
    }

    // ==================== failure handling ====================

    @Test
    void remainingNeverGoesNegative() {
        String user = uniqueUser("remaining");
        for (int i = 0; i < 30; i++) {
            RateLimitResult result = fixedWindow.checkWithInfo(user, 10);
            assertTrue(result.getRemaining() >= 0, "remaining must never be negative");
        }
    }
}
