package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.device.DeviceKind;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** The enrolled, non-revoked device that sent the current request, resolved from its token on every request. */
public record CurrentDevice(
        UUID deviceId, UUID tenantId, UUID venueId, DeviceKind kind, String name, Instant lastSeenAt) {

    static final String ATTRIBUTE = CurrentDevice.class.getName();

    public static Optional<CurrentDevice> of(HttpServletRequest request) {
        return Optional.ofNullable((CurrentDevice) request.getAttribute(ATTRIBUTE));
    }

    public static Optional<CurrentDevice> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                (CurrentDevice) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }
}
