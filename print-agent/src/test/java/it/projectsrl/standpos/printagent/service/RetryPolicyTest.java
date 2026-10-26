package it.projectsrl.standpos.printagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void defaultGivesFiveAttemptsWithGrowingWaits() {
        RetryPolicy policy = RetryPolicy.DEFAULT;

        assertThat(policy.maxAttempts()).isEqualTo(5);
        assertThat(policy.delayAfter(1)).contains(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(2)).contains(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(3)).contains(Duration.ofSeconds(15));
        assertThat(policy.delayAfter(4)).contains(Duration.ofSeconds(30));
        assertThat(policy.delayAfter(5)).isEmpty();
        assertThat(policy.delayAfter(6)).isEmpty();
    }

    @Test
    void reconnectBackoffDoublesUpToTheCap() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 0.5);

        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(8));
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(16));
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(32));
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(60));
        for (int i = 0; i < 100; i++) {
            assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(60));
        }
        backoff.reset();
        assertThat(backoff.next()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void jitterStaysWithinTwentyPercentAndUnderTheCap() {
        Backoff low = new Backoff(Duration.ofSeconds(10), Duration.ofSeconds(60), () -> 0.0);
        Backoff high = new Backoff(Duration.ofSeconds(10), Duration.ofSeconds(60), () -> 0.999);

        assertThat(low.next()).isEqualTo(Duration.ofSeconds(8));
        assertThat(high.next().toMillis()).isBetween(11_900L, 12_000L);
        for (int i = 0; i < 10; i++) {
            assertThat(high.next()).isLessThanOrEqualTo(Duration.ofSeconds(60));
        }
    }
}
