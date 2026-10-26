package it.projectsrl.standpos.print.protocol;

import java.util.List;

/**
 * Sent by the agent every {@link AgentDestinations#HEARTBEAT_SECONDS} seconds to {@link AgentDestinations#HEARTBEAT}.
 * {@code localPrinters} lists the printer names the Windows spooler offers, to help the manager configure the printer;
 * {@code pendingJobs} is the size of the agent's local queue.
 */
public record AgentHeartbeat(String agentVersion, List<String> localPrinters, int pendingJobs) {

    public AgentHeartbeat {
        localPrinters = localPrinters == null ? List.of() : List.copyOf(localPrinters);
    }
}
