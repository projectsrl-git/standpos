package it.projectsrl.standpos.printagent.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * How a failed print is retried: one wait per further attempt. With {@link #DEFAULT} a job gets five attempts over
 * about a minute (2 s, 5 s, 15 s, 30 s between them), long enough for a printer that was briefly off or reconnecting
 * on USB; after that the job is reported FAILED and the cashier reprints once the printer is fixed.
 */
public record RetryPolicy(List<Duration> delays) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(List.of(
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30)));

    public RetryPolicy {
        delays = List.copyOf(delays);
    }

    public int maxAttempts() {
        return delays.size() + 1;
    }

    /** The wait before the next attempt after {@code failedAttempts} failures, or empty when it is time to give up. */
    public Optional<Duration> delayAfter(int failedAttempts) {
        if (failedAttempts < 1 || failedAttempts > delays.size()) {
            return Optional.empty();
        }
        return Optional.of(delays.get(failedAttempts - 1));
    }
}
