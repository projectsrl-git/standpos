package it.projectsrl.standpos.printagent.service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential reconnection backoff (1 s, 2 s, 4 s ... capped at 60 s) with ±20% jitter, so that the registers of a
 * venue do not all reconnect in the same instant after the server restarts. Not thread-safe; used by the connection
 * loop only.
 */
public final class Backoff {

    public static final Duration INITIAL = Duration.ofSeconds(1);
    public static final Duration MAX = Duration.ofSeconds(60);

    private final long initialMillis;
    private final long maxMillis;
    private final DoubleSupplier random;
    private int failures;

    public Backoff() {
        this(INITIAL, MAX, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random values in [0, 1) */
    public Backoff(Duration initial, Duration max, DoubleSupplier random) {
        this.initialMillis = initial.toMillis();
        this.maxMillis = max.toMillis();
        this.random = random;
    }

    public Duration next() {
        long base = initialMillis << Math.min(failures, 20);
        base = Math.min(base, maxMillis);
        failures++;
        double jitter = 0.8 + 0.4 * random.getAsDouble();
        return Duration.ofMillis(Math.min(maxMillis, Math.round(base * jitter)));
    }

    public void reset() {
        failures = 0;
    }
}
