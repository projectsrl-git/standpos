package it.projectsrl.standpos.printagent.service;

import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.print.protocol.AgentHeartbeat;
import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.config.AgentConfig;
import it.projectsrl.standpos.printagent.printer.JobPrinter;
import it.projectsrl.standpos.printagent.queue.JobQueue;
import it.projectsrl.standpos.printagent.queue.StoredJob;
import it.projectsrl.standpos.printagent.stomp.HandshakeRejectedException;
import it.projectsrl.standpos.printagent.stomp.StompFrame;
import it.projectsrl.standpos.printagent.stomp.StompSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The {@code run} command: keeps a STOMP session to the server open, stores every job it receives in the local queue
 * before printing it, prints through the {@link PrintDispatcher}, acknowledges outcomes and sends a heartbeat every
 * {@link AgentDestinations#HEARTBEAT_SECONDS} seconds.
 *
 * <p>Reconnection uses {@link Backoff}; a 401/403 on the handshake (revoked device) is retried only every few minutes.
 * The server re-sends unacknowledged jobs after every connection: a job already finished locally is acknowledged
 * again without printing, a job still queued is ignored. Acknowledgements are persisted and sent again after a
 * reconnection until written to the socket.
 */
public final class AgentService implements AutoCloseable {

    private static final System.Logger log = System.getLogger(AgentService.class.getName());

    /** Timings, overridable in tests. */
    public record Settings(
            Duration heartbeatInterval,
            Duration connectTimeout,
            Duration unauthorizedRetry,
            Duration stableSession,
            Duration pruneInterval,
            RetryPolicy retryPolicy) {

        public static final Settings DEFAULT = new Settings(
                Duration.ofSeconds(AgentDestinations.HEARTBEAT_SECONDS),
                Duration.ofSeconds(15),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofHours(6),
                RetryPolicy.DEFAULT);
    }

    private final AgentConfig config;
    private final JobQueue queue;
    private final Supplier<List<String>> localPrinters;
    private final String agentVersion;
    private final Settings settings;
    private final HttpClient http;
    private final Clock clock;
    private final Backoff backoff;
    private final PrintDispatcher dispatcher;
    private final ExecutorService inbound =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("inbound").daemon().factory());
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("scheduler").daemon().factory());
    private final CompletableFuture<String> stopRequested = new CompletableFuture<>();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile StompSession session;

    public AgentService(AgentConfig config, JobQueue queue, JobPrinter printer, Supplier<List<String>> localPrinters,
            String agentVersion, Settings settings, PrintDispatcher.Sleeper sleeper, Backoff backoff,
            HttpClient http, Clock clock) {
        this.config = config;
        this.queue = queue;
        this.localPrinters = localPrinters;
        this.agentVersion = agentVersion;
        this.settings = settings;
        this.http = http;
        this.clock = clock;
        this.backoff = backoff;
        this.dispatcher = new PrintDispatcher(queue, printer, settings.retryPolicy(), sleeper, this::sendAck);
    }

    /** Runs until {@link #stop()}; then stops the printer workers. */
    public void run() {
        try {
            log.log(System.Logger.Level.INFO, "Print agent {0} starting for server {1}", agentVersion,
                    config.serverUrl());
            int pruned = queue.prune();
            if (pruned > 0) {
                log.log(System.Logger.Level.INFO, "Pruned {0} finished jobs older than 7 days", pruned);
            }
            resumeQueuedJobs();
            long heartbeat = settings.heartbeatInterval().toMillis();
            scheduler.scheduleAtFixedRate(guard(this::sendHeartbeat), heartbeat, heartbeat, TimeUnit.MILLISECONDS);
            long prune = settings.pruneInterval().toMillis();
            scheduler.scheduleAtFixedRate(guard(queue::prune), prune, prune, TimeUnit.MILLISECONDS);
            connectionLoop();
        } finally {
            scheduler.shutdownNow();
            inbound.shutdownNow();
            dispatcher.close();
            log.log(System.Logger.Level.INFO, "Print agent stopped");
            terminated.countDown();
        }
    }

    private void connectionLoop() {
        URI uri = config.webSocketUri(AgentDestinations.ENDPOINT);
        while (!stopRequested.isDone()) {
            Duration delay;
            long started = clock.millis();
            try {
                StompSession s = StompSession.open(http, uri, config.token(), settings.connectTimeout(),
                        this::onMessage);
                session = s;
                log.log(System.Logger.Level.INFO, "Connected to {0}", uri);
                s.subscribe(AgentDestinations.JOBS, "jobs");
                flushPendingAcks();
                sendHeartbeat();
                String reason = waitUntilClosed(s);
                session = null;
                if (stopRequested.isDone()) {
                    break;
                }
                if (clock.millis() - started >= settings.stableSession().toMillis()) {
                    backoff.reset();
                }
                delay = backoff.next();
                log.log(System.Logger.Level.WARNING, "Disconnected: {0}; reconnecting in {1} s", reason,
                        seconds(delay));
            } catch (HandshakeRejectedException e) {
                if (e.isUnauthorized()) {
                    delay = settings.unauthorizedRetry();
                    log.log(System.Logger.Level.ERROR, "The server rejected this agent''s token (HTTP {0}): the "
                            + "device was revoked or the token is invalid. Enrol again with ''enrol'' (see "
                            + "docs/print-agent-setup.md). Retrying in {1} min.", e.status(), delay.toMinutes());
                } else {
                    delay = backoff.next();
                    log.log(System.Logger.Level.WARNING, "Connection refused by the server (HTTP {0}); retrying "
                            + "in {1} s", e.status(), seconds(delay));
                }
            } catch (IOException e) {
                delay = backoff.next();
                log.log(System.Logger.Level.WARNING, "Cannot connect to {0}: {1}; retrying in {2} s", uri,
                        e.getMessage(), seconds(delay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                stopRequested.get(delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // Time to reconnect
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                break;
            }
        }
        StompSession s = session;
        if (s != null) {
            s.close();
        }
    }

    private String waitUntilClosed(StompSession s) throws InterruptedException {
        try {
            Object result = CompletableFuture.anyOf(s.closed(), stopRequested).get();
            if (stopRequested.isDone()) {
                s.close();
            }
            return String.valueOf(result);
        } catch (ExecutionException e) {
            return String.valueOf(e.getCause());
        }
    }

    private void resumeQueuedJobs() {
        List<StoredJob> queued = queue.queued();
        if (!queued.isEmpty()) {
            log.log(System.Logger.Level.INFO, "Resuming {0} queued jobs", queued.size());
        }
        for (StoredJob stored : queued) {
            try {
                PrintJob job = Json.MAPPER.readValue(stored.payload(), PrintJob.class);
                dispatcher.submit(job, stored.attempts());
            } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                fail(stored.jobId(), "invalid job: " + PrintDispatcher.shortError(e));
            }
        }
    }

    private void onMessage(StompFrame frame) {
        String body = frame.body();
        inbound.execute(() -> {
            try {
                receive(body);
            } catch (RuntimeException e) {
                log.log(System.Logger.Level.ERROR, "Cannot handle a received job", e);
            }
        });
    }

    /** Stores a job before printing it, or answers a redelivery. */
    void receive(String body) {
        JsonNode node;
        UUID jobId;
        try {
            node = Json.MAPPER.readTree(body);
            jobId = UUID.fromString(node.path("jobId").asString(""));
        } catch (JacksonException | IllegalArgumentException e) {
            log.log(System.Logger.Level.WARNING, "Dropped a message without a valid jobId");
            return;
        }
        Optional<StoredJob> existing = queue.find(jobId);
        if (existing.isPresent()) {
            StoredJob stored = existing.get();
            if (stored.isFinished()) {
                log.log(System.Logger.Level.INFO, "Job {0} redelivered, already {1}: acknowledging again", jobId,
                        stored.status());
                sendAck(stored.ack());
            } else {
                log.log(System.Logger.Level.DEBUG, "Job {0} redelivered while still queued", jobId);
            }
            return;
        }
        String printer = node.path("printer").path("windowsName").asString("?");
        queue.insertIfAbsent(jobId, printer, body);
        int version = node.path("document").path("version").asInt(0);
        if (version > PrintDocument.CURRENT_VERSION) {
            fail(jobId, "unsupported document version " + version);
            return;
        }
        PrintJob job;
        try {
            job = Json.MAPPER.treeToValue(node, PrintJob.class);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            fail(jobId, "invalid job: " + PrintDispatcher.shortError(e));
            return;
        }
        log.log(System.Logger.Level.INFO, "Received job {0} ({1}) for printer {2}", jobId, job.kind(), printer);
        dispatcher.submit(job, 0);
    }

    private void fail(UUID jobId, String error) {
        log.log(System.Logger.Level.WARNING, "Job {0} refused: {1}", jobId, error);
        sendAck(queue.finish(jobId, JobAck.Status.FAILED, 0, error));
    }

    /** Writes an ack to the socket if connected; it stays pending in the queue otherwise. */
    void sendAck(JobAck ack) {
        StompSession s = session;
        if (s == null || !s.isOpen()) {
            return;
        }
        s.sendJson(AgentDestinations.ACK, Json.MAPPER.writeValueAsString(ack))
                .thenRun(() -> queue.ackDelivered(ack.jobId()))
                .exceptionally(e -> {
                    log.log(System.Logger.Level.DEBUG, "Ack of job {0} will be sent again later", ack.jobId());
                    return null;
                });
    }

    private void flushPendingAcks() {
        List<JobAck> pending = queue.pendingAcks();
        if (!pending.isEmpty()) {
            log.log(System.Logger.Level.INFO, "Sending {0} pending acknowledgements", pending.size());
        }
        pending.forEach(this::sendAck);
    }

    private void sendHeartbeat() {
        StompSession s = session;
        if (s == null || !s.isOpen()) {
            return;
        }
        AgentHeartbeat heartbeat = new AgentHeartbeat(agentVersion, localPrinters.get(), queue.queuedCount());
        s.sendJson(AgentDestinations.HEARTBEAT, Json.MAPPER.writeValueAsString(heartbeat));
    }

    /** A scheduled task that throws is never run again: log and carry on instead. */
    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.log(System.Logger.Level.WARNING, "Scheduled task failed: {0}", e.getMessage());
            }
        };
    }

    private static long seconds(Duration d) {
        return Math.max(1, Math.round(d.toMillis() / 1000.0));
    }

    /** Asks {@link #run()} to return; safe from any thread, including a shutdown hook. */
    public void stop() {
        stopRequested.complete("stop requested");
        StompSession s = session;
        if (s != null) {
            s.close();
        }
    }

    /** Waits for {@link #run()} to finish after {@link #stop()}. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isConnected() {
        StompSession s = session;
        return s != null && s.isOpen();
    }

    @Override
    public void close() {
        stop();
    }
}
