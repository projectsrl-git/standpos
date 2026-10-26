package it.projectsrl.standpos.printagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

    @TempDir
    Path dir;

    @Test
    void savesAndLoadsWithoutLeakingTheTokenInToString() throws Exception {
        AgentConfig config = new AgentConfig(URI.create("https://pos.example.org"), "d1", "v1", "Agente cassa 1",
                "s3cr3t-token", true);
        Path file = dir.resolve("agent.properties");

        config.save(file);
        AgentConfig back = AgentConfig.load(file);

        assertThat(back).isEqualTo(config);
        assertThat(back.toString()).doesNotContain("s3cr3t-token");
        assertThat(Files.readString(file)).contains("device.token=s3cr3t-token");
    }

    @Test
    void derivesTheWebSocketUrl() {
        assertThat(config("https://pos.example.org/").webSocketUri("/ws"))
                .isEqualTo(URI.create("wss://pos.example.org/ws"));
        assertThat(config("https://example.org/standpos").webSocketUri("/ws"))
                .isEqualTo(URI.create("wss://example.org/standpos/ws"));
        assertThat(config("http://localhost:8081").webSocketUri("/ws"))
                .isEqualTo(URI.create("ws://localhost:8081/ws"));
    }

    @Test
    void acceptsPlainHttpOnlyForDevelopmentHosts() {
        assertThat(AgentConfig.parseServerUrl("http://127.0.0.1:8081")).hasToString("http://127.0.0.1:8081");
        assertThat(AgentConfig.parseServerUrl("http://standpos.test")).hasToString("http://standpos.test");
        assertThatThrownBy(() -> AgentConfig.parseServerUrl("http://pos.example.org"))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> AgentConfig.parseServerUrl("pos.example.org")).hasMessageContaining("https://");
    }

    @Test
    void missingConfigurationAsksToEnrol() {
        assertThatThrownBy(() -> AgentConfig.load(dir.resolve("agent.properties"))).hasMessageContaining("enrol");
    }

    @Test
    void resolvesTheDataDirectory() {
        assertThat(AgentHome.resolve(null, Map.of("ProgramData", "C:\\ProgramData")).dir())
                .isEqualTo(Path.of("C:\\ProgramData", "StandPOS", "PrintAgent"));
        assertThat(AgentHome.resolve(null, Map.of(AgentHome.ENV, dir.toString(), "ProgramData", "x")).dir())
                .isEqualTo(dir.toAbsolutePath());
        assertThat(AgentHome.resolve(dir.resolve("h").toString(), Map.of(AgentHome.ENV, "y")).dir())
                .isEqualTo(dir.resolve("h").toAbsolutePath());
    }

    private static AgentConfig config(String url) {
        return new AgentConfig(AgentConfig.parseServerUrl(url), null, null, null, "t", true);
    }
}
