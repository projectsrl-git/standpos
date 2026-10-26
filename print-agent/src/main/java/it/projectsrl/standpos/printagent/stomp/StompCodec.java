package it.projectsrl.standpos.printagent.stomp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * STOMP 1.2 text framing over WebSocket text messages: {@code COMMAND\n header:value\n ... \n\n body NUL}. Header
 * names and values are escaped ({@code \\ \n \r :}) except in {@code CONNECT} and {@code CONNECTED} frames, as the
 * specification requires. A bare end-of-line between frames is a heart-beat.
 *
 * <p>The {@link Decoder} is incremental because a WebSocket message may carry a partial frame, several frames, or
 * heart-beats; a {@code content-length} header (in UTF-8 bytes) is honoured, otherwise the body ends at the NUL.
 */
public final class StompCodec {

    public static final String HEARTBEAT = "\n";

    private StompCodec() {}

    public static String encode(StompFrame frame) {
        boolean escape = !isConnectFrame(frame.command());
        StringBuilder out = new StringBuilder(64 + frame.body().length());
        out.append(frame.command()).append('\n');
        for (Map.Entry<String, String> h : frame.headers().entrySet()) {
            out.append(escape ? escape(h.getKey()) : h.getKey())
                    .append(':')
                    .append(escape ? escape(h.getValue()) : h.getValue())
                    .append('\n');
        }
        if (!frame.body().isEmpty() && !frame.headers().containsKey("content-length")) {
            out.append("content-length:").append(frame.body().getBytes(StandardCharsets.UTF_8).length).append('\n');
        }
        out.append('\n').append(frame.body()).append('\0');
        return out.toString();
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case ':' -> out.append("\\c");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= s.length()) {
                throw new StompProtocolException("Dangling escape in header: " + s);
            }
            switch (s.charAt(i)) {
                case '\\' -> out.append('\\');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'c' -> out.append(':');
                default -> throw new StompProtocolException("Undefined escape \\" + s.charAt(i) + " in header");
            }
        }
        return out.toString();
    }

    private static boolean isConnectFrame(String command) {
        return command.equals("CONNECT") || command.equals("CONNECTED") || command.equals("STOMP");
    }

    /** What the decoder found in the stream. */
    public sealed interface Event {}

    public record FrameEvent(StompFrame frame) implements Event {}

    public record HeartbeatEvent() implements Event {}

    /** Not thread-safe: feed it from the one thread that receives the socket's messages. */
    public static final class Decoder {

        private final StringBuilder buffer = new StringBuilder();

        public List<Event> feed(CharSequence chunk) {
            buffer.append(chunk);
            List<Event> events = new ArrayList<>();
            while (true) {
                int heartbeats = skipEndOfLines();
                for (int i = 0; i < heartbeats; i++) {
                    events.add(new HeartbeatEvent());
                }
                if (buffer.isEmpty()) {
                    return events;
                }
                StompFrame frame = tryFrame();
                if (frame == null) {
                    return events;
                }
                events.add(new FrameEvent(frame));
            }
        }

        private int skipEndOfLines() {
            int count = 0;
            int i = 0;
            while (i < buffer.length()) {
                char c = buffer.charAt(i);
                if (c == '\n') {
                    count++;
                    i++;
                } else if (c == '\r' && i + 1 < buffer.length() && buffer.charAt(i + 1) == '\n') {
                    count++;
                    i += 2;
                } else if (c == '\r') {
                    break; // wait for the '\n'
                } else {
                    break;
                }
            }
            buffer.delete(0, i);
            return count;
        }

        /** Parses one frame from the start of the buffer, or returns null and keeps the buffer if it is incomplete. */
        private StompFrame tryFrame() {
            int pos = 0;
            int eol = buffer.indexOf("\n", pos);
            if (eol < 0) {
                return null;
            }
            String command = stripCr(buffer.substring(pos, eol));
            pos = eol + 1;
            boolean escaped = !isConnectFrame(command);
            Map<String, String> headers = new LinkedHashMap<>();
            while (true) {
                eol = buffer.indexOf("\n", pos);
                if (eol < 0) {
                    return null;
                }
                String line = stripCr(buffer.substring(pos, eol));
                pos = eol + 1;
                if (line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    throw new StompProtocolException("Malformed header line in " + command + " frame");
                }
                String name = line.substring(0, colon);
                String value = line.substring(colon + 1);
                headers.putIfAbsent(escaped ? unescape(name) : name, escaped ? unescape(value) : value);
            }
            int bodyStart = pos;
            int bodyEnd;
            String contentLength = headers.get("content-length");
            if (contentLength != null) {
                int bytes;
                try {
                    bytes = Integer.parseInt(contentLength.trim());
                } catch (NumberFormatException e) {
                    throw new StompProtocolException("Invalid content-length: " + contentLength);
                }
                bodyEnd = charsForUtf8Bytes(bodyStart, bytes);
                if (bodyEnd < 0 || bodyEnd >= buffer.length()) {
                    return null;
                }
                if (buffer.charAt(bodyEnd) != '\0') {
                    throw new StompProtocolException("Frame body not terminated by NUL after content-length");
                }
            } else {
                bodyEnd = buffer.indexOf("\0", bodyStart);
                if (bodyEnd < 0) {
                    return null;
                }
            }
            String body = buffer.substring(bodyStart, bodyEnd);
            buffer.delete(0, bodyEnd + 1);
            return new StompFrame(command, headers, body);
        }

        /** The buffer index after {@code bytes} UTF-8 bytes from {@code start}, or -1 if not all have arrived. */
        private int charsForUtf8Bytes(int start, int bytes) {
            int i = start;
            int counted = 0;
            while (counted < bytes) {
                if (i >= buffer.length()) {
                    return -1;
                }
                int cp = Character.codePointAt(buffer, i);
                counted += utf8Length(cp);
                i += Character.charCount(cp);
            }
            return i;
        }

        private static int utf8Length(int cp) {
            if (cp < 0x80) {
                return 1;
            } else if (cp < 0x800) {
                return 2;
            } else if (cp < 0x10000) {
                return 3;
            }
            return 4;
        }

        private static String stripCr(String line) {
            return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
    }
}
