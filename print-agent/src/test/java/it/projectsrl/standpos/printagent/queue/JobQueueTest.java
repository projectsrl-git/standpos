package it.projectsrl.standpos.printagent.queue;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.print.protocol.JobAck;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobQueueTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));

    @Test
    void jobsAndPendingAcksSurviveAReopen() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (JobQueue queue = open()) {
            assertThat(queue.insertIfAbsent(first, "POS80", "{\"n\":1}")).isTrue();
            assertThat(queue.insertIfAbsent(second, "POS80", "{\"n\":2}")).isTrue();
            queue.recordAttempt(second, 2, "printer not found: POS80");
            queue.finish(first, JobAck.Status.PRINTED, 1, "ignored for printed");
        }

        try (JobQueue queue = open()) {
            assertThat(queue.queued()).singleElement().satisfies(job -> {
                assertThat(job.jobId()).isEqualTo(second);
                assertThat(job.payload()).isEqualTo("{\"n\":2}");
                assertThat(job.attempts()).isEqualTo(2);
                assertThat(job.error()).isEqualTo("printer not found: POS80");
            });
            assertThat(queue.queuedCount()).isEqualTo(1);
            assertThat(queue.pendingAcks()).containsExactly(new JobAck(first, JobAck.Status.PRINTED, 1, null));
            assertThat(queue.find(first).orElseThrow().ack()).isEqualTo(new JobAck(first, JobAck.Status.PRINTED, 1,
                    null));
        }
    }

    @Test
    void deduplicatesByJobId() {
        UUID id = UUID.randomUUID();
        try (JobQueue queue = open()) {
            assertThat(queue.insertIfAbsent(id, "POS80", "{\"first\":true}")).isTrue();
            assertThat(queue.insertIfAbsent(id, "POS80", "{\"first\":false}")).isFalse();

            assertThat(queue.queued()).singleElement().extracting(StoredJob::payload).isEqualTo("{\"first\":true}");
        }
    }

    @Test
    void keepsArrivalOrder() {
        try (JobQueue queue = open()) {
            UUID a = UUID.fromString("ffffffff-0000-0000-0000-000000000000");
            UUID b = UUID.fromString("00000000-0000-0000-0000-000000000001");
            queue.insertIfAbsent(a, "P", "a");
            queue.insertIfAbsent(b, "P", "b");

            assertThat(queue.queued()).extracting(StoredJob::jobId).containsExactly(a, b);
        }
    }

    @Test
    void deliveredAcksAreClearedAndFailuresKeepTheirError() {
        UUID id = UUID.randomUUID();
        try (JobQueue queue = open()) {
            queue.insertIfAbsent(id, "POS80", "{}");
            JobAck ack = queue.finish(id, JobAck.Status.FAILED, 5, "spooler error");

            assertThat(ack).isEqualTo(new JobAck(id, JobAck.Status.FAILED, 5, "spooler error"));
            assertThat(queue.find(id).orElseThrow().ack()).isEqualTo(ack);
            queue.ackDelivered(id);
            assertThat(queue.pendingAcks()).isEmpty();
            assertThat(queue.queuedCount()).isZero();
        }
    }

    @Test
    void prunesFinishedJobsAfterSevenDaysOnceAcknowledged() {
        UUID printed = UUID.randomUUID();
        UUID unacked = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        try (JobQueue queue = open()) {
            queue.insertIfAbsent(printed, "P", "{}");
            queue.insertIfAbsent(unacked, "P", "{}");
            queue.insertIfAbsent(queued, "P", "{}");
            queue.finish(printed, JobAck.Status.PRINTED, 1, null);
            queue.finish(unacked, JobAck.Status.PRINTED, 1, null);
            queue.ackDelivered(printed);

            clock.advance(Duration.ofDays(6));
            assertThat(queue.prune()).isZero();
            clock.advance(Duration.ofDays(2));
            assertThat(queue.prune()).isEqualTo(1);

            assertThat(queue.find(printed)).isEmpty();
            assertThat(queue.find(unacked)).isPresent();
            assertThat(queue.find(queued)).isPresent();
        }
    }

    private JobQueue open() {
        return JobQueue.open(dir.resolve("queue.db"), clock);
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
