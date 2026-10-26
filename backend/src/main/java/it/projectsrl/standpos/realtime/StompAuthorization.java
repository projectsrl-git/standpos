package it.projectsrl.standpos.realtime;

import it.projectsrl.standpos.auth.CurrentDevice;
import it.projectsrl.standpos.auth.MembershipRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.device.Device;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.print.protocol.AgentDestinations;
import it.projectsrl.standpos.tenant.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authorises every inbound STOMP frame (docs/adr/0009-stomp-security.md). Deny by default:
 *
 * <ul>
 *   <li>CONNECT needs the principal of the HTTP handshake.
 *   <li>SUBSCRIBE {@code /topic/venues/{venueId}/…}: a cashier or device of that venue, or a user holding a role on
 *       it (owners: every venue). SUBSCRIBE {@code /user/queue/print-jobs}: print agents only.
 *   <li>SEND {@code /app/agent/…}: print agents only, re-checked against revocation on every frame.
 *   <li>Anything else is refused.
 * </ul>
 */
@Component
class StompAuthorization implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthorization.class);
    private static final Pattern VENUE_TOPIC =
            Pattern.compile("^/topic/venues/([0-9a-fA-F-]{36})/(stock|printers|print-jobs)$");

    private final MembershipRepository memberships;
    private final DeviceRepository devices;

    StompAuthorization(MembershipRepository memberships, DeviceRepository devices) {
        this.memberships = memberships;
        this.devices = devices;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heart-beats
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) {
            return message;
        }
        StandposPrincipal principal = StandposPrincipal.from(accessor.getUser())
                .orElseThrow(() -> new MessageDeliveryException("Not authenticated"));
        CurrentDevice device = device(accessor).orElse(null);
        boolean allowed = switch (command) {
            case CONNECT, STOMP -> true;
            case SUBSCRIBE -> canSubscribe(principal, device, accessor.getDestination());
            case SEND -> isLivePrintAgent(principal, device) && destinationIsAgent(accessor.getDestination());
            default -> false;
        };
        if (!allowed) {
            log.info("STOMP {} to {} refused for {}", command, accessor.getDestination(), principal.getName());
            // A short message: the ERROR frame must not echo the session's headers back to the client.
            throw new MessageDeliveryException("Access denied");
        }
        return message;
    }

    private boolean canSubscribe(StandposPrincipal principal, CurrentDevice device, String destination) {
        if (destination == null) {
            return false;
        }
        if (AgentDestinations.JOBS.equals(destination)) {
            return isLivePrintAgent(principal, device);
        }
        Matcher topic = VENUE_TOPIC.matcher(destination);
        if (!topic.matches()) {
            return false;
        }
        UUID venueId = UUID.fromString(topic.group(1));
        return switch (principal.kind()) {
            case CASHIER, DEVICE -> venueId.equals(principal.venueId());
            case USER -> TenantContext.callAs(principal.tenantId(), () -> memberships
                    .findAllByUserId(principal.userId()).stream()
                    .anyMatch(m -> m.getRole() == Role.OWNER || venueId.equals(m.getVenueId())));
        };
    }

    /** A print agent whose device is still enrolled: revocation takes effect on its next frame. */
    private boolean isLivePrintAgent(StandposPrincipal principal, CurrentDevice device) {
        if (principal.kind() != StandposPrincipal.Kind.DEVICE || device == null
                || device.kind() != DeviceKind.PRINT_AGENT || !device.deviceId().equals(principal.deviceId())) {
            return false;
        }
        return TenantContext.callAs(principal.tenantId(), () -> devices.findById(principal.deviceId())
                .filter(d -> !d.isRevoked())
                .map(Device::getKind)
                .filter(k -> k == DeviceKind.PRINT_AGENT)
                .isPresent());
    }

    private static boolean destinationIsAgent(String destination) {
        return AgentDestinations.ACK.equals(destination) || AgentDestinations.HEARTBEAT.equals(destination);
    }

    private static Optional<CurrentDevice> device(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        return Optional.ofNullable(attributes == null ? null : (CurrentDevice) attributes.get(WebSocketConfig.DEVICE_ATTRIBUTE));
    }
}
