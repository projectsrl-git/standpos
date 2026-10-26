package it.projectsrl.standpos.printagent.stomp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A STOMP 1.2 frame: command, headers in order (the first occurrence of a repeated header wins) and a text body. */
public record StompFrame(String command, Map<String, String> headers, String body) {

    public StompFrame {
        Objects.requireNonNull(command, "command");
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Map.of() : headers));
        body = body == null ? "" : body;
    }

    public String header(String name) {
        return headers.get(name);
    }

    public static Builder builder(String command) {
        return new Builder(command);
    }

    /** Ordered headers, so encoded frames are predictable in tests and logs. */
    public static final class Builder {

        private final String command;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body = "";

        private Builder(String command) {
            this.command = command;
        }

        public Builder header(String name, String value) {
            headers.putIfAbsent(name, value);
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public StompFrame build() {
            return new StompFrame(command, headers, body);
        }
    }
}
