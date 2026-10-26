package it.projectsrl.standpos.realtime;

import it.projectsrl.standpos.printing.PrintEvents;
import it.projectsrl.standpos.stock.StockChanged;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes venue events to the registers once the transaction that caused them has committed: stock and sold-out
 * changes, printer online/offline, finished print jobs. Payloads carry ids and states only, no personal data.
 */
@Component
class VenueBroadcaster {

    private final SimpMessagingTemplate messaging;

    VenueBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void stock(StockChanged event) {
        messaging.convertAndSend(topic(event.venueId(), "stock"), event);
    }

    @TransactionalEventListener(fallbackExecution = true)
    void jobFinished(PrintEvents.JobFinished event) {
        messaging.convertAndSend(topic(event.venueId(), "print-jobs"), event);
    }

    /** Presence changes happen outside transactions (heartbeats, the sweep). */
    @EventListener
    void agentStatus(PrintEvents.AgentStatus event) {
        messaging.convertAndSend(topic(event.venueId(), "printers"), event);
    }

    static String topic(java.util.UUID venueId, String name) {
        return "/topic/venues/" + venueId + "/" + name;
    }
}
