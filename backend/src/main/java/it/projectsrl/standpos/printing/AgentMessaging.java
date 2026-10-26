package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.print.protocol.AgentHeartbeat;
import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.tenant.TenantContext;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * The server side of the print agent protocol ({@link AgentDestinations}). Frames reach these handlers already
 * authorised (print agents only) and with the agent's tenant in context.
 *
 * <p>Delivery: a new job goes out right after its transaction commits when the agent is connected; every heartbeat
 * also delivers what is still pending (jobs queued while the agent was offline, and unacknowledged ones).
 */
@Controller
class AgentMessaging {

    private static final String JOBS_QUEUE = AgentDestinations.JOBS.substring("/user".length());

    private final PrintService prints;
    private final AgentPresence presence;
    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry users;
    private final TransactionTemplate afterCommit;

    AgentMessaging(PrintService prints, AgentPresence presence, SimpMessagingTemplate messaging,
            SimpUserRegistry users, PlatformTransactionManager transactionManager) {
        this.prints = prints;
        this.presence = presence;
        this.messaging = messaging;
        this.users = users;
        this.afterCommit = new TransactionTemplate(transactionManager);
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @MessageMapping("/agent/heartbeat")
    void heartbeat(AgentHeartbeat heartbeat, Principal user) {
        StandposPrincipal agent = agent(user);
        presence.heartbeat(agent.tenantId(), agent.venueId(), agent.deviceId(), heartbeat);
        deliver(agent.deviceId(), prints.takeForDelivery(agent.deviceId(), null));
    }

    @MessageMapping("/agent/ack")
    void ack(JobAck ack, Principal user) {
        prints.acknowledge(agent(user).deviceId(), ack);
    }

    /** After commit: push the new job now if its agent is connected; otherwise it waits for the next heartbeat. */
    @TransactionalEventListener(fallbackExecution = true)
    void jobQueued(PrintEvents.JobQueued event) {
        if (users.getUser(StandposPrincipal.deviceUserName(event.agentId())) == null) {
            return;
        }
        // A new transaction: after commit, the finished one is still bound to the thread.
        List<PrintJob> jobs = TenantContext.callAs(event.tenantId(), () -> afterCommit.execute(
                status -> prints.takeForDelivery(event.agentId(), List.of(event.jobId()))));
        deliver(event.agentId(), jobs);
    }

    /** Offline at once; if the agent still has another connection, its next heartbeat turns it back online. */
    @EventListener
    void disconnected(SessionDisconnectEvent event) {
        StandposPrincipal.from(event.getUser())
                .filter(p -> p.kind() == StandposPrincipal.Kind.DEVICE)
                .ifPresent(p -> presence.disconnected(p.deviceId()));
    }

    private void deliver(UUID agentId, Collection<PrintJob> jobs) {
        for (PrintJob job : jobs) {
            messaging.convertAndSendToUser(StandposPrincipal.deviceUserName(agentId), JOBS_QUEUE, job);
        }
    }

    private static StandposPrincipal agent(Principal user) {
        return StandposPrincipal.from(user).orElseThrow(() -> new IllegalStateException("No agent principal"));
    }
}
