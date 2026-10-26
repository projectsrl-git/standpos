package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.print.protocol.AgentHeartbeat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Which print agents are alive, from their heartbeats (every 15 s). An agent is online while its last heartbeat is
 * less than three intervals old; changes are announced to the venue's registers ("stampante offline").
 *
 * <p>Kept in memory: v1 runs one backend instance, and agents reconnect and send a heartbeat within seconds of a
 * restart. Scaling out needs a shared store (open question in the ADR).
 */
@Component
public class AgentPresence {

    static final Duration OFFLINE_AFTER = Duration.ofSeconds(AgentDestinations.HEARTBEAT_SECONDS * 3L);

    public record State(UUID tenantId, UUID venueId, Instant lastHeartbeat, String agentVersion,
            List<String> localPrinters, int pendingJobs, boolean online) {}

    private final Map<UUID, State> agents = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AgentPresence(ApplicationEventPublisher events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public void heartbeat(UUID tenantId, UUID venueId, UUID agentId, AgentHeartbeat heartbeat) {
        Instant now = clock.instant();
        State previous = agents.put(agentId, new State(tenantId, venueId, now, heartbeat.agentVersion(),
                heartbeat.localPrinters(), heartbeat.pendingJobs(), true));
        if (previous == null || !previous.online()) {
            events.publishEvent(new PrintEvents.AgentStatus(venueId, agentId, true));
        }
    }

    public void disconnected(UUID agentId) {
        State previous = agents.computeIfPresent(agentId, (id, s) -> offline(s));
        if (previous != null) {
            events.publishEvent(new PrintEvents.AgentStatus(previous.venueId(), agentId, false));
        }
    }

    public boolean isOnline(UUID agentId) {
        State state = agents.get(agentId);
        return state != null && state.online() && fresh(state, clock.instant());
    }

    public Optional<State> state(UUID agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** Turns agents whose heartbeats stopped offline and tells their venues. */
    @Scheduled(fixedDelay = 5_000)
    public void sweep() {
        Instant now = clock.instant();
        agents.forEach((agentId, state) -> {
            if (state.online() && !fresh(state, now) && agents.replace(agentId, state, offline(state))) {
                events.publishEvent(new PrintEvents.AgentStatus(state.venueId(), agentId, false));
            }
        });
    }

    private static boolean fresh(State state, Instant now) {
        return state.lastHeartbeat().plus(OFFLINE_AFTER).isAfter(now);
    }

    private static State offline(State s) {
        return new State(s.tenantId(), s.venueId(), s.lastHeartbeat(), s.agentVersion(), s.localPrinters(),
                s.pendingJobs(), false);
    }
}
