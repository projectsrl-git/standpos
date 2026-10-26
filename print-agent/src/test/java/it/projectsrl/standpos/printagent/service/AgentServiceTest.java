package it.projectsrl.standpos.printagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.print.protocol.AgentHeartbeat;
import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.config.AgentConfig;
import it.projectsrl.standpos.printagent.printer.EscPosJobPrinter;
import it.projectsrl.standpos.printagent.printer.Printers;
import it.projectsrl.standpos.printagent.queue.JobQueue;
import it.projectsrl.standpos.printagent.stomp.HandshakeRejectedException;
import it.projectsrl.standpos.printagent.stomp.StompFrame;
import it.projectsrl.standpos.printagent.stomp.StompSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end in the JVM: the agent against {@link FakeStompServer}, printing into a file. */
class AgentServiceTest {

    private static final String TOKEN = "test-device-token";

    @TempDir
    Path dir;

    private FakeStompServer server;
    private JobQueue queue;
    private AgentService service;
    private Thread runner;

    @BeforeEach
    void start() throws Exception {
        server = new FakeStompServer(TOKEN);
        queue = JobQueue.open(dir.resolve("queue.db"), Clock.systemUTC());
    }

    @AfterEach
    void stop() throws Exception {
        if (service != null) {
            service.stop();
            assertThat(service.awaitTermination(Duration.ofSeconds(10))).isTrue();
        }
        queue.close();
        server.close();
    }

    @Test
    void printsAcknowledgesDeduplicatesAndReconnects() throws Exception {
        startAgent(Duration.ofSeconds(60));
        StompFrame subscribe = server.await(f -> f.command().equals("SUBSCRIBE"), 10);
        assertThat(subscribe.header("destination")).isEqualTo(AgentDestinations.JOBS);

        // The first heartbeat follows the SUBSCRIBE at once (the server delivers pending jobs on it), not after 60 s
        StompFrame heartbeat = server.await(FakeStompServer.sendTo(AgentDestinations.HEARTBEAT), 5);
        AgentHeartbeat beat = Json.MAPPER.readValue(heartbeat.body(), AgentHeartbeat.class);
        assertThat(beat.localPrinters()).containsExactly("POS80");
        assertThat(beat.agentVersion()).isEqualTo("test");

        Path paper = dir.resolve("paper.bin");
        PrintJob job = job(paper, PrintDocument.builder().text("Scontrino di cortesia – documento non fiscale").cut()
                .build());
        server.sendMessage(Json.MAPPER.writeValueAsString(job));

        JobAck ack = ack(server.await(FakeStompServer.sendTo(AgentDestinations.ACK), 10));
        assertThat(ack).isEqualTo(new JobAck(job.jobId(), JobAck.Status.PRINTED, 1, null));
        long printedBytes = Files.size(paper);
        assertThat(printedBytes).isPositive();
        assertThat(queue.pendingAcks()).isEmpty();

        // The server re-sends: acknowledged again, not printed again
        server.sendMessage(Json.MAPPER.writeValueAsString(job));
        assertThat(ack(server.await(FakeStompServer.sendTo(AgentDestinations.ACK), 10))).isEqualTo(ack);
        assertThat(Files.size(paper)).isEqualTo(printedBytes);

        // A network failure: the agent reconnects and subscribes again
        server.dropConnection();
        server.await(f -> f.command().equals("SUBSCRIBE"), 10);
        assertThat(server.connections.get()).isEqualTo(2);
    }

    @Test
    void refusesDocumentsNewerThanItUnderstands() throws Exception {
        startAgent();
        server.await(f -> f.command().equals("SUBSCRIBE"), 10);
        Path paper = dir.resolve("paper.bin");
        PrintJob job = job(paper, PrintDocument.builder().text("x").build());
        String json = Json.MAPPER.writeValueAsString(job).replace("\"version\":1", "\"version\":99")
                .replace("\"type\":\"text\"", "\"type\":\"qrcode\"");

        server.sendMessage(json);

        JobAck ack = ack(server.await(FakeStompServer.sendTo(AgentDestinations.ACK), 10));
        assertThat(ack.status()).isEqualTo(JobAck.Status.FAILED);
        assertThat(ack.error()).isEqualTo("unsupported document version 99");
        assertThat(paper).doesNotExist();
    }

    @Test
    void sendsAcksStoredWhileDisconnectedAndResumesQueuedJobs() throws Exception {
        Path paper = dir.resolve("paper.bin");
        UUID printedEarlier = UUID.randomUUID();
        queue.insertIfAbsent(printedEarlier, "POS80", "{}");
        queue.finish(printedEarlier, JobAck.Status.PRINTED, 2, null);
        PrintJob queued = job(paper, PrintDocument.builder().text("queued before a reboot").build());
        queue.insertIfAbsent(queued.jobId(), queued.printer().windowsName(), Json.MAPPER.writeValueAsString(queued));

        startAgent();

        List<JobAck> acks = List.of(
                ack(server.await(FakeStompServer.sendTo(AgentDestinations.ACK), 10)),
                ack(server.await(FakeStompServer.sendTo(AgentDestinations.ACK), 10)));
        assertThat(acks).containsExactlyInAnyOrder(
                new JobAck(printedEarlier, JobAck.Status.PRINTED, 2, null),
                new JobAck(queued.jobId(), JobAck.Status.PRINTED, 1, null));
        assertThat(paper).exists();
    }

    @Test
    void aRevokedTokenIsReportedAsUnauthorized() throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + server.port() + "/ws");

        assertThatThrownBy(() -> StompSession.open(HttpClient.newHttpClient(), uri, "revoked", Duration.ofSeconds(5),
                        f -> {}))
                .isInstanceOfSatisfying(HandshakeRejectedException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.isUnauthorized()).isTrue();
                });
        assertThat(server.rejectedHandshakes.get()).isEqualTo(1);
    }

    private void startAgent() {
        startAgent(Duration.ofMillis(300));
    }

    private void startAgent(Duration heartbeatInterval) {
        AgentConfig config = new AgentConfig(URI.create("http://127.0.0.1:" + server.port()), "device", "venue",
                "Agente test", TOKEN, false);
        AgentService.Settings settings = new AgentService.Settings(heartbeatInterval, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofHours(1),
                new RetryPolicy(List.of(Duration.ofMillis(10))));
        service = new AgentService(config, queue, new EscPosJobPrinter(false), () -> List.of("POS80"), "test",
                settings, d -> Thread.sleep(d.toMillis()),
                new Backoff(Duration.ofMillis(50), Duration.ofMillis(200), () -> 0.5), HttpClient.newHttpClient(),
                Clock.systemUTC());
        runner = Thread.ofPlatform().name("agent-under-test").start(service::run);
    }

    private static PrintJob job(Path paper, PrintDocument document) {
        return new PrintJob(UUID.randomUUID(), PrintJob.Kind.RECEIPT,
                new PrintJob.PrinterTarget(Printers.FILE_PREFIX + paper, 48, "CP858"), document);
    }

    private static JobAck ack(StompFrame frame) {
        assertThat(frame.header("content-type")).isEqualTo("application/json");
        return Json.MAPPER.readValue(frame.body(), JobAck.class);
    }
}
