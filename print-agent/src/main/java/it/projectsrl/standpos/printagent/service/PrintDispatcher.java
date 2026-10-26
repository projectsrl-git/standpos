package it.projectsrl.standpos.printagent.service;

import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.printer.JobPrinter;
import it.projectsrl.standpos.printagent.queue.JobQueue;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Prints queued jobs one at a time per printer, in arrival order: each printer has its own single-threaded worker, so
 * a printer that is retrying holds back only its own jobs. Each attempt is recorded in the queue; the outcome and its
 * acknowledgement are stored together, then handed to the ack sink for delivery.
 *
 * <p>On shutdown the workers are interrupted; an interrupted job stays QUEUED and resumes after the restart.
 */
public final class PrintDispatcher implements AutoCloseable {

    private static final System.Logger log = System.getLogger(PrintDispatcher.class.getName());

    /** Waits between retries; replaced in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final JobQueue queue;
    private final JobPrinter printer;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final Consumer<JobAck> ackSink;
    private final Map<String, ExecutorService> workers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PrintDispatcher(JobQueue queue, JobPrinter printer, RetryPolicy policy, Sleeper sleeper,
            Consumer<JobAck> ackSink) {
        this.queue = queue;
        this.printer = printer;
        this.policy = policy;
        this.sleeper = sleeper;
        this.ackSink = ackSink;
    }

    /** Queues a job (already stored in the {@link JobQueue}) on its printer's worker. */
    public void submit(PrintJob job, int previousAttempts) {
        if (closed) {
            return;
        }
        String key = job.printer().windowsName().trim().toLowerCase(Locale.ROOT);
        ExecutorService worker = workers.computeIfAbsent(key, k -> Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("printer-" + workers.size()).factory()));
        worker.execute(() -> print(job, previousAttempts));
    }

    private void print(PrintJob job, int previousAttempts) {
        int attempts = previousAttempts;
        while (!Thread.currentThread().isInterrupted()) {
            attempts++;
            String error;
            try {
                printer.print(job);
                log.log(System.Logger.Level.INFO, "Printed job {0} ({1}) on {2}, attempt {3}", job.jobId(),
                        job.kind(), job.printer().windowsName(), attempts);
                deliver(queue.finish(job.jobId(), JobAck.Status.PRINTED, attempts, null));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                error = shortError(e);
            }
            Optional<Duration> delay = policy.delayAfter(attempts);
            if (delay.isEmpty()) {
                log.log(System.Logger.Level.WARNING, "Job {0} failed after {1} attempts: {2}", job.jobId(), attempts,
                        error);
                deliver(queue.finish(job.jobId(), JobAck.Status.FAILED, attempts, error));
                return;
            }
            log.log(System.Logger.Level.WARNING, "Job {0} attempt {1} failed ({2}); retrying in {3} s", job.jobId(),
                    attempts, error, delay.get().toSeconds());
            queue.recordAttempt(job.jobId(), attempts, error);
            try {
                sleeper.sleep(delay.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void deliver(JobAck ack) {
        try {
            ackSink.accept(ack);
        } catch (RuntimeException e) {
            // The ack is stored; it is sent again on the next connection
            log.log(System.Logger.Level.WARNING, "Ack of job {0} not sent now: {1}", ack.jobId(), e.getMessage());
        }
    }

    static String shortError(Throwable e) {
        String message = e.getMessage();
        String text = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /** Stops the workers, waiting briefly for a print in progress to finish. */
    @Override
    public void close() {
        closed = true;
        workers.values().forEach(ExecutorService::shutdownNow);
        for (ExecutorService worker : workers.values()) {
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.log(System.Logger.Level.WARNING, "A printer worker did not stop in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
