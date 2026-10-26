package it.projectsrl.standpos.printagent.stomp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One STOMP 1.2 session over a plain WebSocket ({@code java.net.http}), authenticated with a bearer token on the
 * handshake. It sends {@code CONNECT}, waits for {@code CONNECTED}, negotiates heart-beats, then delivers
 * {@code MESSAGE} frames to a handler. The session ends on close, error, an {@code ERROR} frame or when the server's
 * heart-beats stop; {@link #closed()} completes with the reason and the caller opens a new session.
 *
 * <p>Sends are serialised, because a {@link WebSocket} allows only one outstanding text send.
 */
public final class StompSession implements AutoCloseable {

    private static final System.Logger log = System.getLogger(StompSession.class.getName());

    /** Heart-beat intervals we offer: we can send every 10 s and want to hear from the server every 10 s. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(10);

    /** Without negotiated server heart-beats, a WebSocket ping is sent this often to detect a dead connection. */
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);

    private final Clock clock;
    private final Consumer<StompFrame> onMessage;
    private final CompletableFuture<StompFrame> connected = new CompletableFuture<>();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ScheduledExecutorService timer;
    private final StompCodec.Decoder decoder = new StompCodec.Decoder();
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;
    private volatile long lastReceived;
    private volatile long lastSent;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    private StompSession(Clock clock, Consumer<StompFrame> onMessage) {
        this.clock = clock;
        this.onMessage = onMessage;
        this.timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("stomp-heartbeat").daemon().factory());
        this.lastReceived = clock.millis();
        this.lastSent = clock.millis();
    }

    /**
     * Opens the WebSocket, completes the STOMP handshake and negotiates heart-beats.
     *
     * @param onMessage called for every {@code MESSAGE} frame, on the WebSocket's thread: hand work off quickly
     * @throws HandshakeRejectedException when the server rejects the HTTP upgrade (401 for a revoked token)
     * @throws IOException when the connection or the STOMP handshake fails
     */
    public static StompSession open(HttpClient http, URI uri, String bearerToken, Duration timeout,
            Consumer<StompFrame> onMessage) throws HandshakeRejectedException, IOException, InterruptedException {
        return open(http, uri, bearerToken, timeout, onMessage, Clock.systemUTC());
    }

    static StompSession open(HttpClient http, URI uri, String bearerToken, Duration timeout,
            Consumer<StompFrame> onMessage, Clock clock)
            throws HandshakeRejectedException, IOException, InterruptedException {
        StompSession session = new StompSession(clock, onMessage);
        try {
            session.socket = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + bearerToken)
                    .connectTimeout(timeout)
                    .buildAsync(uri, session.new Listener())
                    .get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            session.shutdownTimer();
            Throwable cause = e.getCause();
            if (cause instanceof WebSocketHandshakeException h) {
                throw new HandshakeRejectedException(h.getResponse().statusCode());
            }
            throw new IOException("WebSocket connection failed: " + describe(cause), cause);
        } catch (TimeoutException e) {
            session.shutdownTimer();
            throw new IOException("WebSocket connection timed out");
        }
        try {
            session.handshake(uri.getHost(), timeout);
        } catch (IOException | RuntimeException e) {
            session.close();
            throw e;
        }
        return session;
    }

    private void handshake(String host, Duration timeout) throws IOException, InterruptedException {
        sendFrame(StompFrame.builder("CONNECT")
                .header("accept-version", "1.2")
                .header("host", host)
                .header("heart-beat", HEARTBEAT.toMillis() + "," + HEARTBEAT.toMillis())
                .build());
        StompFrame frame;
        try {
            frame = connected.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("STOMP connect failed: " + describe(e.getCause()), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("No CONNECTED frame within " + timeout.toSeconds() + " s");
        }
        long[] beats = negotiate(frame.header("heart-beat"));
        startTimers(beats[0], beats[1]);
    }

    /**
     * STOMP heart-beat negotiation: we send every max(ours, theirs-wanted) and expect to receive every
     * max(ours-wanted, theirs), 0 meaning none. Returns {sendMillis, receiveMillis}.
     */
    static long[] negotiate(String serverHeartBeat) {
        long cx = HEARTBEAT.toMillis();
        long cy = HEARTBEAT.toMillis();
        long sx = 0;
        long sy = 0;
        if (serverHeartBeat != null) {
            String[] parts = serverHeartBeat.split(",");
            if (parts.length == 2) {
                try {
                    sx = Long.parseLong(parts[0].trim());
                    sy = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException e) {
                    sx = 0;
                    sy = 0;
                }
            }
        }
        long send = cx == 0 || sy == 0 ? 0 : Math.max(cx, sy);
        long receive = cy == 0 || sx == 0 ? 0 : Math.max(cy, sx);
        return new long[] {send, receive};
    }

    private void startTimers(long sendMillis, long receiveMillis) {
        if (sendMillis > 0) {
            timer.scheduleAtFixedRate(() -> {
                if (clock.millis() - lastSent >= sendMillis - 500) {
                    sendRaw(StompCodec.HEARTBEAT);
                }
            }, sendMillis, sendMillis, TimeUnit.MILLISECONDS);
        }
        long watch = receiveMillis > 0 ? receiveMillis : PING_INTERVAL.toMillis();
        long deadline = receiveMillis > 0 ? receiveMillis * 3 : PING_INTERVAL.toMillis() * 3;
        timer.scheduleAtFixedRate(() -> {
            if (receiveMillis == 0) {
                WebSocket ws = socket;
                if (ws != null) {
                    ws.sendPing(ByteBuffer.allocate(0));
                }
            }
            if (clock.millis() - lastReceived > deadline) {
                abort("no data from the server for " + deadline / 1000 + " s");
            }
        }, watch, watch, TimeUnit.MILLISECONDS);
    }

    public void subscribe(String destination, String id) {
        sendFrame(StompFrame.builder("SUBSCRIBE").header("id", id).header("destination", destination)
                .header("ack", "auto").build());
    }

    /** Sends a JSON body to an application destination; completes when the frame has been written to the socket. */
    public CompletableFuture<?> sendJson(String destination, String json) {
        return sendFrame(StompFrame.builder("SEND")
                .header("destination", destination)
                .header("content-type", "application/json")
                .body(json)
                .build());
    }

    private CompletableFuture<?> sendFrame(StompFrame frame) {
        return sendRaw(StompCodec.encode(frame));
    }

    private synchronized CompletableFuture<?> sendRaw(String text) {
        WebSocket ws = socket;
        if (ws == null || closed.isDone()) {
            return CompletableFuture.failedFuture(new IOException("session closed"));
        }
        sendChain = sendChain.handle((ok, error) -> null).thenCompose(ignored -> ws.sendText(text, true));
        sendChain.whenComplete((ok, error) -> {
            if (error == null) {
                lastSent = clock.millis();
            } else {
                abort("send failed: " + describe(error));
            }
        });
        return sendChain;
    }

    /** Completes with the reason when the session is over. */
    public CompletableFuture<String> closed() {
        return closed;
    }

    public boolean isOpen() {
        return !closed.isDone();
    }

    /** Ends the session gracefully (DISCONNECT, then a normal close). */
    @Override
    public void close() {
        if (closing.compareAndSet(false, true)) {
            WebSocket ws = socket;
            if (ws != null && !closed.isDone()) {
                try {
                    sendFrame(StompFrame.builder("DISCONNECT").build()).get(2, TimeUnit.SECONDS);
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException e) {
                    // Closing anyway
                }
                ws.abort();
            }
            finish("closed by the agent");
        }
    }

    private void abort(String reason) {
        WebSocket ws = socket;
        if (ws != null) {
            ws.abort();
        }
        finish(reason);
    }

    private void finish(String reason) {
        if (closed.complete(reason)) {
            connected.completeExceptionally(new IOException(reason));
            shutdownTimer();
        }
    }

    private void shutdownTimer() {
        timer.shutdownNow();
    }

    private void onFrame(StompFrame frame) {
        switch (frame.command()) {
            case "CONNECTED" -> connected.complete(frame);
            case "MESSAGE" -> {
                try {
                    onMessage.accept(frame);
                } catch (RuntimeException e) {
                    log.log(System.Logger.Level.ERROR, "Message handler failed", e);
                }
            }
            case "ERROR" -> {
                String message = frame.header("message");
                String reason = "server ERROR frame: " + (message != null ? message : frame.body().strip());
                connected.completeExceptionally(new IOException(reason));
                abort(reason);
            }
            case "RECEIPT" -> {
                // Not requested; ignored
            }
            default -> log.log(System.Logger.Level.DEBUG, "Ignoring STOMP frame {0}", frame.command());
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    /** Receives WebSocket messages, reassembles partial text and feeds the decoder. */
    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            lastReceived = clock.millis();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastReceived = clock.millis();
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                try {
                    List<StompCodec.Event> events = decoder.feed(message);
                    for (StompCodec.Event event : events) {
                        if (event instanceof StompCodec.FrameEvent(StompFrame frame)) {
                            onFrame(frame);
                        }
                    }
                } catch (StompProtocolException e) {
                    abort("protocol error: " + e.getMessage());
                    return null;
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            lastReceived = clock.millis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastReceived = clock.millis();
            webSocket.request(1);
            return null; // the JDK answers with a pong
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastReceived = clock.millis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            finish("closed by the server (" + statusCode + (reason.isBlank() ? "" : " " + reason) + ")");
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            finish("connection error: " + describe(error));
        }
    }
}
