package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.print.protocol.AgentHeartbeat;
import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.tenant.TenantContext;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * The server side of the print agent protocol ({@link AgentDestinations}). Frames reach these handlers already
 * authorised (print agents only) and with the agent's tenant in context.
 *
 * <p>Delivery: a new job goes out right after its transaction commits when the agent is subscribed; every heartbeat
 * also delivers what is still pending (jobs queued while the agent was offline, and unacknowledged ones).
 */
@Controller
class AgentMessaging {

    private static final String JOBS_QUEUE = AgentDestinations.JOBS.substring("/user".length());
    private static final Duration SUBSCRIBE_DELAY = Duration.ofSeconds(1);

    private final PrintService prints;
    private final AgentPresence presence;
    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry users;
    private final TransactionTemplate afterCommit;
    private final TaskScheduler scheduler;

    AgentMessaging(PrintService prints, AgentPresence presence, SimpMessagingTemplate messaging,
            SimpUserRegistry users, PlatformTransactionManager transactionManager,
            @Qualifier("taskScheduler") TaskScheduler scheduler) {
        this.prints = prints;
        this.presence = presence;
        this.messaging = messaging;
        this.users = users;
        this.afterCommit = new TransactionTemplate(transactionManager);
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.scheduler = scheduler;
    }

    @MessageMapping("/agent/heartbeat")
    void heartbeat(AgentHeartbeat heartbeat, Principal user) {
        StandposPrincipal agent = agent(user);
        presence.heartbeat(agent.tenantId(), agent.venueId(), agent.deviceId(), heartbeat);
        if (subscribed(agent.deviceId())) {
            deliver(agent.deviceId(), prints.takeForDelivery(agent.deviceId(), null));
        }
    }

    /**
     * Frames of a session are handled concurrently, so a heartbeat sent right after SUBSCRIBE may be handled before
     * the broker registers the subscription. A delivery one second after the subscription covers that case.
     */
    @EventListener
    void onSubscribe(SessionSubscribeEvent event) {
        String destination = SimpMessageHeaderAccessor.getDestination(event.getMessage().getHeaders());
        StandposPrincipal.from(event.getUser())
                .filter(p -> p.kind() == StandposPrincipal.Kind.DEVICE && AgentDestinations.JOBS.equals(destination))
                .ifPresent(agent -> scheduler.schedule(() -> TenantContext.runAs(agent.tenantId(),
                        () -> deliver(agent.deviceId(), prints.takeForDelivery(agent.deviceId(), null))),
                        Instant.now().plus(SUBSCRIBE_DELAY)));
    }

    @MessageMapping("/agent/ack")
    void ack(JobAck ack, Principal user) {
        prints.acknowledge(agent(user).deviceId(), ack);
    }

    /** After commit: push the new job now if its agent is connected; otherwise it waits for the next heartbeat. */
    @TransactionalEventListener(fallbackExecution = true)
    void jobQueued(PrintEvents.JobQueued event) {
        if (!subscribed(event.agentId())) {
            return; // offline: the job waits for the agent's next connection
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

    /** The agent has a session subscribed to its job queue (jobs sent otherwise would be lost, then re-sent). */
    private boolean subscribed(UUID agentId) {
        SimpUser user = users.getUser(StandposPrincipal.deviceUserName(agentId));
        return user != null && user.getSessions().stream()
                .flatMap(s -> s.getSubscriptions().stream())
                .anyMatch(s -> AgentDestinations.JOBS.equals(s.getDestination()));
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
