package it.projectsrl.standpos.printagent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The agent's data directory: {@code agent.properties} (server, device name, token), {@code queue.db} and
 * {@code logs/}. Defaults to {@code %ProgramData%\StandPOS\PrintAgent}, which the installer restricts to SYSTEM and
 * Administrators because the file holds the device token; {@code --home} or {@code STANDPOS_AGENT_HOME} override it.
 */
public record AgentHome(Path dir) {

    public static final String ENV = "STANDPOS_AGENT_HOME";

    public static AgentHome resolve(String homeOption, Map<String, String> env) {
        if (homeOption != null && !homeOption.isBlank()) {
            return new AgentHome(Path.of(homeOption).toAbsolutePath());
        }
        String fromEnv = env.get(ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new AgentHome(Path.of(fromEnv).toAbsolutePath());
        }
        String programData = env.get("ProgramData");
        if (programData == null || programData.isBlank()) {
            programData = env.get("PROGRAMDATA");
        }
        if (programData != null && !programData.isBlank()) {
            return new AgentHome(Path.of(programData, "StandPOS", "PrintAgent"));
        }
        // Not Windows (development only)
        return new AgentHome(Path.of(System.getProperty("user.home"), ".standpos", "print-agent"));
    }

    public Path configFile() {
        return dir.resolve("agent.properties");
    }

    public Path queueFile() {
        return dir.resolve("queue.db");
    }

    public Path logsDir() {
        return dir.resolve("logs");
    }

    public AgentHome create() throws IOException {
        Files.createDirectories(dir);
        return this;
    }
}
