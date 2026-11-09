package com.ratelimitx.core.circuitbreaker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test moves by hand, so timeout behaviour is exercised without sleeping.
 */
public class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public static MutableClock startingNow() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
