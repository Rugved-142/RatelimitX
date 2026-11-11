package com.ratelimitx.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.ratelimitx.core.model.RateLimitResult;
import com.ratelimitx.core.support.AbstractIntegrationTest;

/**
 * Time-dependent behaviour against a real Redis, with real waiting.
 *
 * <p>The window is shortened to 2 seconds so the tests observe an actual rollover rather than
 * asserting on arithmetic. These are the only tests in the suite that sleep; a mocked clock cannot
 * be used here, because the window arithmetic deliberately happens inside Redis.
 */
@TestPropertySource(properties = {
        "ratelimit.window-size-seconds=2",
        "ratelimit.max-requests=5"
})
class WindowRolloverIT extends AbstractIntegrationTest {

    private static final Duration WINDOW = Duration.ofSeconds(2);

    @Autowired
    private RateLimiterService fixedWindow;

    @Autowired
    private TokenBucketService tokenBucket;

    @Autowired
    private SlidingWindowService slidingWindow;

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    @Test
    void fixedWindowAdmitsTheFullLimitAgainInTheNextWindow() throws Exception {
        String user = uniqueUser("fw-rollover");
        int limit = 5;

        for (int i = 0; i < limit; i++) {
            assertTrue(fixedWindow.checkWithInfo(user, limit).isAllowed());
        }
        RateLimitResult denied = fixedWindow.checkWithInfo(user, limit);
        assertFalse(denied.isAllowed());

        // The header says exactly how long until the window resets; trust it and wait that long.
        sleep(Duration.ofMillis(denied.getResetTime() + 250));

        int allowedAfter = 0;
        for (int i = 0; i < limit + 2; i++) {
            if (fixedWindow.checkWithInfo(user, limit).isAllowed()) {
                allowedAfter++;
            }
        }
        assertEquals(limit, allowedAfter,
                "the next window should admit the full limit again, and no more");
    }

    @Test
    void fixedWindowStaysDeniedBeforeTheWindowEnds() throws Exception {
        String user = uniqueUser("fw-early");
        for (int i = 0; i < 5; i++) {
            fixedWindow.checkWithInfo(user, 5);
        }

        RateLimitResult denied = fixedWindow.checkWithInfo(user, 5);
        assertFalse(denied.isAllowed());

        // Wait almost, but not quite, to the boundary.
        long waitMs = denied.getResetTime() - 600;
        if (waitMs > 0) {
            sleep(Duration.ofMillis(waitMs));
            assertFalse(fixedWindow.checkWithInfo(user, 5).isAllowed(),
                    "still inside the window, so still denied");
        }
    }

    @Test
    void slidingWindowRecoversGraduallyRatherThanAllAtOnce() throws Exception {
        String user = uniqueUser("sw-rollover");
        int limit = 5;

        for (int i = 0; i < limit; i++) {
            assertTrue(slidingWindow.checkRateLimit(user, limit).isAllowed());
        }
        assertFalse(slidingWindow.checkRateLimit(user, limit).isAllowed());

        // Half a window later the previous window still counts for about half its requests, so
        // capacity has partly returned but not fully. This is the property that distinguishes a
        // sliding window from a fixed one.
        sleep(WINDOW.dividedBy(2));
        int allowedMidway = 0;
        for (int i = 0; i < limit; i++) {
            if (slidingWindow.checkRateLimit(user, limit).isAllowed()) {
                allowedMidway++;
            }
        }
        assertTrue(allowedMidway < limit,
                "half a window in, the previous window must still hold some weight, got "
                        + allowedMidway + " of " + limit);

        // Two full windows later nothing is left of the old counts.
        sleep(WINDOW.multipliedBy(2));
        int allowedAfter = 0;
        for (int i = 0; i < limit + 2; i++) {
            if (slidingWindow.checkRateLimit(user, limit).isAllowed()) {
                allowedAfter++;
            }
        }
        assertEquals(limit, allowedAfter, "after two windows the full limit is available again");
    }

    @Test
    void tokenBucketRefillsAtTheTierRate() throws Exception {
        // Capacity equals the limit and the refill rate is limit/60 per second, so a limit of 120
        // refills 2 tokens a second.
        String user = uniqueUser("tb-refill");
        int limit = 120;

        int drained = 0;
        for (int i = 0; i < limit + 10; i++) {
            if (tokenBucket.tryConsume(user, limit).isAllowed()) {
                drained++;
            }
        }
        assertTrue(drained >= limit, "the bucket starts full, so at least " + limit + " pass");
        assertFalse(tokenBucket.tryConsume(user, limit).isAllowed(), "the bucket is empty now");

        sleep(Duration.ofSeconds(2));

        int afterRefill = 0;
        for (int i = 0; i < 10; i++) {
            if (tokenBucket.tryConsume(user, limit).isAllowed()) {
                afterRefill++;
            }
        }
        // 2 tokens per second for 2 seconds, with a little slack for the time the drain took.
        assertTrue(afterRefill >= 3 && afterRefill <= 8,
                "roughly 4 tokens should have refilled in 2 seconds, got " + afterRefill);
    }

    @Test
    void tokenBucketRefillRateFollowsTheLimit() throws Exception {
        // A smaller limit must refill more slowly: 60/min is 1 token per second.
        String slow = uniqueUser("tb-slow");
        int limit = 60;

        for (int i = 0; i < limit + 5; i++) {
            tokenBucket.tryConsume(slow, limit);
        }
        assertFalse(tokenBucket.tryConsume(slow, limit).isAllowed());

        sleep(Duration.ofMillis(1200));

        int afterRefill = 0;
        for (int i = 0; i < 5; i++) {
            if (tokenBucket.tryConsume(slow, limit).isAllowed()) {
                afterRefill++;
            }
        }
        assertTrue(afterRefill >= 1 && afterRefill <= 3,
                "about 1 token should refill per second at a limit of 60, got " + afterRefill);
    }

    @Test
    void tokenBucketNeverExceedsCapacityHoweverLongItIsIdle() throws Exception {
        String user = uniqueUser("tb-cap");
        int limit = 10;

        tokenBucket.tryConsume(user, limit);
        // Idle for several windows; the bucket must cap at capacity, not keep accruing.
        sleep(Duration.ofSeconds(3));

        int allowed = 0;
        for (int i = 0; i < limit + 10; i++) {
            if (tokenBucket.tryConsume(user, limit).isAllowed()) {
                allowed++;
            }
        }
        assertTrue(allowed <= limit + 1,
                "an idle bucket must not accrue beyond capacity, got " + allowed);
    }
}
