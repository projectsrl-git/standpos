package it.projectsrl.standpos.printagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.printer.PrintFailedException;
import it.projectsrl.standpos.printagent.queue.JobQueue;
import it.projectsrl.standpos.printagent.queue.StoredJob;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintDispatcherTest {

    @TempDir
    Path dir;

    private JobQueue queue;
    private final BlockingQueue<JobAck> acks = new LinkedBlockingQueue<>();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();

    @BeforeEach
    void open() {
        queue = JobQueue.open(dir.resolve("queue.db"), Clock.systemUTC());
    }

    @AfterEach
    void close() {
        queue.close();
    }

    @Test
    void retriesWithBackoffThenAcknowledgesPrintedWithTheAttemptCount() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PrintJob job = job("POS80");
        try (PrintDispatcher dispatcher = dispatcher(j -> {
            if (calls.incrementAndGet() < 3) {
                throw new PrintFailedException("printer not found: POS80");
            }
        })) {
            dispatcher.submit(job, 0);

            assertThat(acks.poll(5, TimeUnit.SECONDS)).isEqualTo(new JobAck(job.jobId(), JobAck.Status.PRINTED, 3,
                    null));
        }
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThat(queue.find(job.jobId()).orElseThrow().status()).isEqualTo(StoredJob.Status.PRINTED);
        assertThat(queue.pendingAcks()).hasSize(1);
    }

    @Test
    void givesUpAfterTheLastAttemptWithTheError() throws Exception {
        PrintJob job = job("POS80");
        try (PrintDispatcher dispatcher = dispatcher(j -> {
            throw new PrintFailedException("spooler error: offline");
        })) {
            dispatcher.submit(job, 0);

            assertThat(acks.poll(5, TimeUnit.SECONDS)).isEqualTo(new JobAck(job.jobId(), JobAck.Status.FAILED, 5,
                    "spooler error: offline"));
        }
        assertThat(sleeps).hasSize(4);
        assertThat(queue.find(job.jobId()).orElseThrow().status()).isEqualTo(StoredJob.Status.FAILED);
    }

    @Test
    void resumedJobsContinueTheirAttemptCount() throws Exception {
        PrintJob job = job("POS80");
        try (PrintDispatcher dispatcher = dispatcher(j -> {})) {
            dispatcher.submit(job, 2);

            assertThat(acks.poll(5, TimeUnit.SECONDS).attempts()).isEqualTo(3);
        }
    }

    @Test
    void printsOneJobAtATimePerPrinterInArrivalOrder() throws Exception {
        List<UUID> printed = new CopyOnWriteArrayList<>();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<PrintJob> jobs = List.of(job("POS80"), job("pos80"), job("POS80"), job("POS80"));
        try (PrintDispatcher dispatcher = dispatcher(j -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            Thread.sleep(20);
            printed.add(j.jobId());
            concurrent.decrementAndGet();
        })) {
            jobs.forEach(j -> dispatcher.submit(j, 0));
            for (int i = 0; i < jobs.size(); i++) {
                assertThat(acks.poll(5, TimeUnit.SECONDS)).isNotNull();
            }
        }
        assertThat(printed).containsExactlyElementsOf(jobs.stream().map(PrintJob::jobId).toList());
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    private PrintDispatcher dispatcher(it.projectsrl.standpos.printagent.printer.JobPrinter printer) {
        return new PrintDispatcher(queue, printer, RetryPolicy.DEFAULT, sleeps::add, acks::add);
    }

    private PrintJob job(String printer) {
        PrintJob job = new PrintJob(UUID.randomUUID(), PrintJob.Kind.RECEIPT,
                new PrintJob.PrinterTarget(printer, 48, "CP858"), PrintDocument.builder().text("x").build());
        queue.insertIfAbsent(job.jobId(), printer, "{}");
        return job;
    }
}
