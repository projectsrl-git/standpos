package it.projectsrl.standpos.print.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * The agent's answer to a {@link PrintJob}, sent to {@link AgentDestinations#ACK}: printed, or failed after the
 * agent's own retries. {@code error} is a short technical text (never personal data).
 */
public record JobAck(UUID jobId, Status status, int attempts, String error) {

    public enum Status {
        PRINTED,
        FAILED
    }

    public JobAck {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(status, "status");
        if (error != null && error.length() > 500) {
            error = error.substring(0, 500);
        }
    }
}
