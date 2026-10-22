package it.projectsrl.standpos.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * Who is calling. Stored in the HTTP session for USER and CASHIER; built per request for a bare DEVICE.
 *
 * <ul>
 *   <li>USER: an owner or manager logged in with email and password.
 *   <li>CASHIER: a user logged in with a PIN on an enrolled register; bound to that device and its venue.
 *   <li>DEVICE: an enrolled device with nobody logged in (PIN page, print agent).
 * </ul>
 *
 * Roles are not cached here: {@link Authz} reads memberships fresh on each request.
 */
public record StandposPrincipal(
        Kind kind, UUID tenantId, UUID userId, String displayName, UUID deviceId, UUID venueId, boolean platformAdmin)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        USER,
        CASHIER,
        DEVICE
    }

    public StandposPrincipal {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(tenantId);
        if (kind != Kind.DEVICE) {
            Objects.requireNonNull(userId);
        }
        if (kind != Kind.USER) {
            Objects.requireNonNull(deviceId);
            Objects.requireNonNull(venueId);
        }
    }

    public static StandposPrincipal user(UUID tenantId, UUID userId, String displayName, boolean platformAdmin) {
        return new StandposPrincipal(Kind.USER, tenantId, userId, displayName, null, null, platformAdmin);
    }

    public static StandposPrincipal cashier(UUID tenantId, UUID userId, String displayName, UUID deviceId, UUID venueId) {
        return new StandposPrincipal(Kind.CASHIER, tenantId, userId, displayName, deviceId, venueId, false);
    }

    public static StandposPrincipal device(CurrentDevice device) {
        return new StandposPrincipal(
                Kind.DEVICE, device.tenantId(), null, device.name(), device.deviceId(), device.venueId(), false);
    }

    @Override
    public String getName() {
        return kind == Kind.DEVICE ? "device:" + deviceId : userId.toString();
    }
}
