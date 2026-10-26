package it.projectsrl.standpos.printing;

import java.util.UUID;

/** Events of the print pipeline, published inside transactions and handled after commit. */
public final class PrintEvents {

    private PrintEvents() {}

    /** A job was queued for a printer driven by {@code agentId}: deliver it if the agent is connected. */
    public record JobQueued(UUID tenantId, UUID jobId, UUID agentId) {}

    /** A job reached a final state; registers of the venue show failures and offer "Ristampa". */
    public record JobFinished(UUID venueId, UUID jobId, UUID orderId, String kind, String status, String error) {}

    /** The receipt of an order came out of the printer. */
    public record ReceiptPrinted(UUID orderId) {}

    /** An agent went online or offline (heartbeats started or stopped). */
    public record AgentStatus(UUID venueId, UUID agentId, boolean online) {}
}
