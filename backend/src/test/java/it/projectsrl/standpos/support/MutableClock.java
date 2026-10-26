package it.projectsrl.standpos.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application clock in integration tests: the system clock unless a test fixes it. Tests that set it reset it
 * afterwards ({@link #reset()}), since all integration tests share one context.
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    public void set(Instant instant) {
        fixed = instant;
    }

    public void reset() {
        fixed = null;
    }

    @Override
    public Instant instant() {
        Instant value = fixed;
        return value != null ? value : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The application clock is UTC");
    }
}
