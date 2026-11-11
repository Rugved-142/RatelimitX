package com.ratelimitx.core.support;

/**
 * The Redis timeout the behavioural integration tests run with.
 *
 * <p>Production uses 200ms, deliberately: a rate limit check sits on the request path, so failing
 * fast and falling back to the local limiter beats making the caller wait. That is the right value
 * for the service and the wrong one for a test runner.
 *
 * <p>Under CPU contention a single Redis command can exceed 200ms for reasons that have nothing to
 * do with the code under test. Measured on a container starved to 2% of a CPU over a one second
 * scheduling period, the suite failed in one run out of five: {@code FLUSHALL} timing out in
 * {@code @BeforeEach}, and tier limits reading as unexhausted because the breaker had fallen back
 * mid-test. Both are the harness being judged against a production latency budget it was never
 * meant to meet.
 *
 * <p>So the tests that verify <strong>rate limiting behaviour</strong> - limits, tiers, headers,
 * window rollover, throttling - declare this timeout. They are not testing the timeout.
 *
 * <p>Two classes deliberately do <strong>not</strong>, and must keep the production value:
 *
 * <ul>
 *   <li>{@code RedisOutageIT}, which asserts that a frozen Redis trips the breaker quickly. With a
 *       relaxed timeout it would be measuring something else entirely.
 *   <li>{@code RedisReadinessIT}, which exists to prove the setup wait works against the real
 *       value. Relaxing it would make that test pass whether or not the wait was there.
 * </ul>
 *
 * <p>Neither hardcodes 200ms: they simply do not override it, so the value comes from
 * {@code application.properties} and there is no second copy to drift.
 * {@code RedisTimeoutConfigTest} fails if that value stops being 200ms, so this relaxed one cannot
 * quietly become the real one.
 */
public final class TestRedisTimeouts {

    /**
     * Long enough that infrastructure latency cannot expire it, short enough that a genuinely hung
     * Redis still fails the build promptly instead of hanging.
     *
     * <p>A compile-time constant so it can be used inside {@code @TestPropertySource}.
     */
    public static final String RELAXED = "3s";

    /** Spring reads these as one property source; keep the order stable so contexts are cached. */
    public static final String COMMAND_TIMEOUT = "spring.data.redis.timeout=" + RELAXED;
    public static final String CONNECT_TIMEOUT = "spring.data.redis.connect-timeout=" + RELAXED;

    private TestRedisTimeouts() {
    }
}
