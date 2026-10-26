package it.projectsrl.standpos.printagent.enrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EnrolmentClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exchangesTheCodeForAToken() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        URI url = serve(201, "application/json", """
                {"device":{"id":"d-1","venueId":"v-1","kind":"PRINT_AGENT","name":"Agente cassa 1"},"token":"tok"}""",
                request);

        EnrolmentClient.Enrolment enrolment = new EnrolmentClient(HttpClient.newHttpClient())
                .enrol(url, " ABCD2345 ", "Agente cassa 1");

        assertThat(enrolment).isEqualTo(new EnrolmentClient.Enrolment("d-1", "v-1", "Agente cassa 1", "tok"));
        assertThat(enrolment.toString()).doesNotContain("tok]");
        assertThat(request.get()).contains("\"code\":\"ABCD2345\"", "\"name\":\"Agente cassa 1\"");
    }

    @Test
    void explainsProblemCodes() throws Exception {
        URI url = serve(400, "application/problem+json", """
                {"type":"about:blank","title":"Bad Request","status":400,"code":"device.not_agent_code"}""", null);

        assertThatThrownBy(() -> new EnrolmentClient(HttpClient.newHttpClient()).enrol(url, "ABCD2345", "x"))
                .isInstanceOfSatisfying(EnrolmentClient.EnrolmentException.class, e -> {
                    assertThat(e.code()).isEqualTo("device.not_agent_code");
                    assertThat(e.getMessage()).contains("Agente di stampa");
                });
    }

    @Test
    void reportsUnexpectedErrors() throws Exception {
        URI url = serve(503, "text/html", "<html>down</html>", null);

        assertThatThrownBy(() -> new EnrolmentClient(HttpClient.newHttpClient()).enrol(url, "ABCD2345", "x"))
                .hasMessageContaining("HTTP 503");
    }

    private URI serve(int status, String contentType, String body, AtomicReference<String> request)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/agents/enrol", exchange -> {
            if (request != null) {
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
