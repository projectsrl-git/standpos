package it.projectsrl.standpos.printagent.service;

import it.projectsrl.standpos.printagent.stomp.StompCodec;
import it.projectsrl.standpos.printagent.stomp.StompFrame;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A minimal WebSocket + STOMP server for in-JVM tests of the agent: checks the bearer token on the handshake (401
 * otherwise), answers CONNECT with CONNECTED and records every other frame the client sends.
 */
final class FakeStompServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final String token;
    private final ServerSocket server;
    private final Thread acceptor;
    private final BlockingQueue<StompFrame> received = new LinkedBlockingQueue<>();
    private final AtomicInteger messageIds = new AtomicInteger();
    final AtomicInteger rejectedHandshakes = new AtomicInteger();
    final AtomicInteger connections = new AtomicInteger();
    private volatile Socket current;

    FakeStompServer(String token) throws IOException {
        this.token = token;
        this.server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofPlatform().daemon().name("fake-stomp").start(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    /** Waits for a client frame matching the predicate, discarding the others. */
    StompFrame await(Predicate<StompFrame> match, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            StompFrame frame = received.poll(100, TimeUnit.MILLISECONDS);
            if (frame != null && match.test(frame)) {
                return frame;
            }
        }
        throw new AssertionError("No matching frame within " + seconds + " s");
    }

    static Predicate<StompFrame> sendTo(String destination) {
        return f -> f.command().equals("SEND") && destination.equals(f.header("destination"));
    }

    void sendMessage(String json) throws IOException {
        sendText(StompCodec.encode(StompFrame.builder("MESSAGE")
                .header("destination", "/user/queue/print-jobs")
                .header("subscription", "jobs")
                .header("message-id", "m-" + messageIds.incrementAndGet())
                .header("content-type", "application/json")
                .body(json)
                .build()));
    }

    void sendText(String text) throws IOException {
        Socket socket = current;
        if (socket == null) {
            throw new IOException("no client connected");
        }
        writeFrame(socket.getOutputStream(), 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Drops the TCP connection without a close handshake, like a network failure. */
    void dropConnection() throws IOException {
        Socket socket = current;
        if (socket != null) {
            socket.close();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofPlatform().daemon().name("fake-stomp-conn").start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Map<String, String> headers = readRequestHeaders(in);
            if (!("Bearer " + token).equals(headers.get("authorization"))) {
                rejectedHandshakes.incrementAndGet();
                out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((headers.get("sec-websocket-key") + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            connections.incrementAndGet();
            current = socket;
            StompCodec.Decoder decoder = new StompCodec.Decoder();
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                if (b0 < 0) {
                    return;
                }
                int opcode = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                byte[] payload = readPayload(in);
                switch (opcode) {
                    case 0x0, 0x1 -> {
                        message.write(payload);
                        if (fin) {
                            String text = message.toString(StandardCharsets.UTF_8);
                            message.reset();
                            for (StompCodec.Event event : decoder.feed(text)) {
                                if (event instanceof StompCodec.FrameEvent(StompFrame frame)) {
                                    onFrame(out, frame);
                                }
                            }
                        }
                    }
                    case 0x8 -> {
                        writeFrame(out, 0x8, new byte[0]);
                        return;
                    }
                    case 0x9 -> writeFrame(out, 0xA, payload);
                    default -> {
                        // pong or binary: ignored
                    }
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            // Connection over
        } finally {
            if (current == socket) {
                current = null;
            }
        }
    }

    private void onFrame(OutputStream out, StompFrame frame) throws IOException {
        if (frame.command().equals("CONNECT")) {
            writeFrame(out, 0x1, StompCodec.encode(StompFrame.builder("CONNECTED")
                    .header("version", "1.2")
                    .header("heart-beat", "0,0")
                    .build()).getBytes(StandardCharsets.UTF_8));
            return;
        }
        received.add(frame);
    }

    private static Map<String, String> readRequestHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        StringBuilder line = new StringBuilder();
        boolean first = true;
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("eof in handshake");
            }
            if (c == '\n') {
                String l = line.toString().strip();
                line.setLength(0);
                if (l.isEmpty()) {
                    return headers;
                }
                if (!first) {
                    int colon = l.indexOf(':');
                    headers.put(l.substring(0, colon).trim().toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
                }
                first = false;
            } else {
                line.append((char) c);
            }
        }
    }

    private static byte[] readPayload(InputStream in) throws IOException {
        int b1 = in.read();
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | in.read();
            }
        }
        byte[] mask = masked ? in.readNBytes(4) : new byte[4];
        byte[] payload = in.readNBytes((int) length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        return payload;
    }

    private static synchronized void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length < 65536) {
            frame.write(126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
            }
        }
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
        dropConnection();
        acceptor.interrupt();
    }
}
