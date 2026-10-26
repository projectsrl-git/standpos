package it.projectsrl.standpos.printagent.enrol;

import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.printagent.config.AgentConfig;
import it.projectsrl.standpos.printagent.service.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Exchanges a one-time enrolment code for a device token on {@link AgentDestinations#ENROL}. The endpoint sets no
 * cookie and needs no CSRF token; the token appears only in this response, so the caller stores it at once.
 */
public final class EnrolmentClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;

    public EnrolmentClient(HttpClient http) {
        this.http = http;
    }

    /** The enrolled device and its token. */
    public record Enrolment(String deviceId, String venueId, String name, String token) {

        @Override
        public String toString() {
            return "Enrolment[deviceId=" + deviceId + ", venueId=" + venueId + ", name=" + name + ", token=***]";
        }
    }

    public Enrolment enrol(URI serverUrl, String code, String name) throws IOException, InterruptedException {
        String body = Json.MAPPER.writeValueAsString(Map.of("code", code.trim(), "name", name));
        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + AgentDestinations.ENROL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, application/problem+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = parse(response.body());
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            String token = json == null ? null : text(json.path("token"));
            if (token == null || token.isBlank()) {
                throw new EnrolmentException(null, "The server answered without a device token");
            }
            JsonNode device = json.path("device");
            return new Enrolment(text(device.path("id")), text(device.path("venueId")), text(device.path("name")),
                    token);
        }
        String errorCode = json == null ? null : text(json.path("code"));
        String detail = json == null ? null : text(json.path("detail"));
        if (detail == null && json != null) {
            detail = text(json.path("title"));
        }
        throw new EnrolmentException(errorCode, explain(errorCode, response.statusCode(), detail));
    }

    static String explain(String code, int status, String detail) {
        if (code != null) {
            switch (code) {
                case "device.invalid_code" -> {
                    return "The code is wrong or expired (codes last 10 minutes). Create a new one in the manager "
                            + "area: Dispositivi > Agente di stampa.";
                }
                case "device.not_agent_code" -> {
                    return "This code is for a register or kitchen screen. Create a code of type 'Agente di stampa'.";
                }
                case "device.rate_limited" -> {
                    return "Too many attempts from this network. Wait a few minutes and try again.";
                }
                default -> {
                    // Fall through to the generic message
                }
            }
        }
        return "Enrolment failed (HTTP " + status + (code != null ? ", " + code : "") + ")"
                + (detail != null ? ": " + detail : "");
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    /** Builds the configuration to save after a successful enrolment. */
    public static AgentConfig toConfig(URI serverUrl, Enrolment enrolment, String requestedName) {
        String name = enrolment.name() != null ? enrolment.name() : requestedName;
        return new AgentConfig(serverUrl, enrolment.deviceId(), enrolment.venueId(), name, enrolment.token(), true);
    }

    /** The server refused the code; {@code code} is the problem-details code when there is one. */
    public static class EnrolmentException extends IOException {

        private final String code;

        public EnrolmentException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
