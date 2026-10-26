package it.projectsrl.standpos.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.projectsrl.standpos.auth.AuthLookup;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.device.DeviceService;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.print.protocol.AgentHeartbeat;
import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printing.AgentPresence;
import it.projectsrl.standpos.printing.PrintJobEntity;
import it.projectsrl.standpos.printing.PrintJobRepository;
import it.projectsrl.standpos.sales.CustomerOrderRepository;
import it.projectsrl.standpos.sales.OrderService;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.MutableClock;
import it.projectsrl.standpos.support.SalesFixtures;
import it.projectsrl.standpos.support.TestDatabase;
import it.projectsrl.standpos.tenant.TenantContext;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebSocket/STOMP against a running server: a print agent authenticated with its bearer token receives the receipt
 * of a sale and acknowledges it, the register receives stock and printer events live, subscriptions outside the
 * caller's rights are refused, and revoking the agent closes its connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({Fixtures.class, SalesFixtures.class, it.projectsrl.standpos.support.TestClockConfig.class})
class RealtimeIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    Fixtures fixtures;

    @Autowired
    SalesFixtures sales;

    @Autowired
    MutableClock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    AgentPresence presence;

    @Autowired
    PrintJobRepository jobs;

    @Autowired
    CustomerOrderRepository orders;

    @Autowired
    DeviceService devices;

    @Autowired
    AuthLookup lookup;

    TenantFixture tenant;
    UUID cashier;
    SalesFixtures.Register register;
    String agentToken;
    UUID agentId;
    UUID panino;
    WebSocketStompClient client;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant("Realtime");
        cashier = fixtures.user(tenant, "Lia", null, null, "1357", tenant.venueId(), Role.CASHIER);
        register = sales.register(tenant, tenant.venueId());
        agentToken = sales.printer(tenant, tenant.venueId(), register);
        agentId = lookup.findDevice(DeviceTokens.hash(agentToken)).orElseThrow().deviceId();
        UUID category = sales.category(tenant, tenant.venueId(), "Cucina");
        panino = sales.product(tenant, tenant.venueId(), category, "Panino", 450, true, 2);
        sales.receive(tenant, tenant.venueId(), panino, 5, 150L);
        sales.openShift(tenant, register, cashier);
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter(json));
    }

    @AfterEach
    void tearDown() {
        client.stop();
        clock.reset();
    }

    @Test
    void theAgentReceivesTheReceiptAcknowledgesItAndTheRegisterSeesStockLive() throws Exception {
        Recorder registerEvents = new Recorder();
        StompSession till = connect(cookie(register.token()), registerEvents);
        BlockingQueue<Map<String, Object>> stock = subscribe(till, "/topic/venues/" + tenant.venueId() + "/stock");
        BlockingQueue<Map<String, Object>> printers =
                subscribe(till, "/topic/venues/" + tenant.venueId() + "/printers");

        StompSession agent = connect(bearer(agentToken), new Recorder());
        BlockingQueue<PrintJob> received = new LinkedBlockingQueue<>();
        agent.subscribe(AgentDestinations.JOBS, handler(PrintJob.class, received::add));
        agent.send(AgentDestinations.HEARTBEAT, new AgentHeartbeat("test", List.of("POS80"), 0));
        await(() -> presence.isOnline(agentId));
        assertThat(printers.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).containsEntry("online", true);

        OrderService.Result sale = sales.sell(tenant, register, cashier, panino, 2, true);
        UUID orderId = sale.view().order().getId();

        PrintJob job = received.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(job).isNotNull();
        assertThat(job.kind()).isEqualTo(PrintJob.Kind.RECEIPT);
        assertThat(job.printer().windowsName()).isEqualTo("file:test");
        assertThat(job.printer().columns()).isEqualTo(48);
        assertThat(json.writeValueAsString(job.document())).contains("Scontrino di cortesia");

        Map<String, Object> change = stock.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(change).containsEntry("productId", panino.toString()).containsEntry("onHand", 3)
                .containsEntry("low", false);

        agent.send(AgentDestinations.ACK, new JobAck(job.jobId(), JobAck.Status.PRINTED, 1, null));
        await(() -> inTenant(() -> jobs.findById(job.jobId()).orElseThrow().getStatus())
                == PrintJobEntity.Status.PRINTED);
        assertThat(inTenant(() -> orders.findById(orderId).orElseThrow().isReceiptPrinted())).isTrue();
        assertThat(registerEvents.errors).isEmpty();
    }

    @Test
    void jobsQueuedWhileTheAgentIsOfflineAreDeliveredOnItsFirstHeartbeat() throws Exception {
        sales.sell(tenant, register, cashier, panino, 1, true);
        sales.sell(tenant, register, cashier, panino, 1, true);

        StompSession agent = connect(bearer(agentToken), new Recorder());
        BlockingQueue<PrintJob> received = new LinkedBlockingQueue<>();
        agent.subscribe(AgentDestinations.JOBS, handler(PrintJob.class, received::add));
        agent.send(AgentDestinations.HEARTBEAT, new AgentHeartbeat("test", List.of(), 0));

        assertThat(received.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).isNotNull();
        assertThat(received.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void subscriptionsOutsideTheCallersRightsAreRefused() throws Exception {
        TenantFixture other = fixtures.tenant("Elsewhere");

        // A register may not take the agent's jobs, nor send agent frames.
        assertThat(rawFrames(cookie(register.token()), "SUBSCRIBE\nid:1\ndestination:" + AgentDestinations.JOBS))
                .anyMatch(f -> f.startsWith("ERROR"));
        assertThat(rawFrames(cookie(register.token()), "SEND\ndestination:" + AgentDestinations.HEARTBEAT
                + "\ncontent-type:application/json\n\n{}")).anyMatch(f -> f.startsWith("ERROR"));
        // An agent may not listen to another tenant's venue.
        assertThat(rawFrames(bearer(agentToken), "SUBSCRIBE\nid:1\ndestination:/topic/venues/" + other.venueId()
                + "/stock")).anyMatch(f -> f.startsWith("ERROR"));
        // Its own venue is fine.
        assertThat(rawFrames(bearer(agentToken), "SUBSCRIBE\nid:1\ndestination:/topic/venues/" + tenant.venueId()
                + "/stock")).noneMatch(f -> f.startsWith("ERROR"));
    }

    /** Sends CONNECT and one frame over a plain WebSocket and returns the frames received within a second. */
    private List<String> rawFrames(WebSocketHttpHeaders headers, String frame) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        var session = new StandardWebSocketClient().execute(new org.springframework.web.socket.handler.TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(org.springframework.web.socket.WebSocketSession s,
                    org.springframework.web.socket.TextMessage message) {
                frames.add(message.getPayload());
            }
        }, headers, java.net.URI.create("ws://localhost:" + port + "/ws")).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        try {
            session.sendMessage(new org.springframework.web.socket.TextMessage(
                    "CONNECT\naccept-version:1.2\nhost:localhost\n\n\0"));
            assertThat(frames.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).startsWith("CONNECTED");
            session.sendMessage(new org.springframework.web.socket.TextMessage(
                    frame + (frame.contains("\n\n") ? "" : "\n\n") + "\0"));
            List<String> received = new java.util.ArrayList<>();
            String next;
            while ((next = frames.poll(1, TimeUnit.SECONDS)) != null) {
                received.add(next);
            }
            return received;
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    @Test
    void theHandshakeNeedsCredentialsAndRevocationClosesTheConnection() throws Exception {
        assertThatThrownBy(() -> connect(new WebSocketHttpHeaders(), new Recorder()))
                .isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> connect(bearer("not-a-token"), new Recorder()))
                .isInstanceOf(ExecutionException.class);

        StompSession agent = connect(bearer(agentToken), new Recorder());
        assertThat(agent.isConnected()).isTrue();
        TenantContext.runAs(tenant.tenantId(), () -> devices.revoke(tenant.venueId(), agentId));
        await(() -> !agent.isConnected());
    }

    // Helpers

    private StompSession connect(WebSocketHttpHeaders headers, Recorder recorder) throws Exception {
        return client.connectAsync("ws://localhost:" + port + "/ws", headers, new StompHeaders(), recorder)
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    private static WebSocketHttpHeaders bearer(String token) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + token);
        return headers;
    }

    private static WebSocketHttpHeaders cookie(String deviceToken) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", DeviceTokens.COOKIE + "=" + deviceToken);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, handler(Map.class, m -> queue.add((Map<String, Object>) m)));
        return queue;
    }

    private static <T> StompFrameHandler handler(Class<T> type, java.util.function.Consumer<T> consumer) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                consumer.accept(type.cast(payload));
            }
        };
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return fixtures.inTenant(tenant.tenantId(), action);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + WAIT);
            }
            Thread.sleep(50);
        }
    }

    /** Records STOMP ERROR frames sent by the server. */
    static final class Recorder extends StompSessionHandlerAdapter {

        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(String.valueOf(headers.getFirst("message")));
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload,
                Throwable exception) {
            errors.add(exception.toString());
        }

        /** The server may close the connection right after its ERROR frame. */
        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add(exception.toString());
        }
    }
}
