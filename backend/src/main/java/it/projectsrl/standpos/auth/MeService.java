package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Describes the current caller to the frontend. */
@Service
public class MeService {

    public record Me(
            StandposPrincipal.Kind kind,
            TenantInfo tenant,
            UserInfo user,
            List<MembershipInfo> memberships,
            DeviceInfo device,
            boolean platformAdmin) {}

    public record TenantInfo(UUID id, String name) {}

    public record UserInfo(UUID id, String displayName, String email) {}

    public record MembershipInfo(UUID id, UUID venueId, Role role) {}

    public record DeviceInfo(UUID id, UUID venueId, DeviceKind kind, String name) {}

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final MembershipRepository memberships;

    public MeService(TenantRepository tenants, AppUserRepository users, MembershipRepository memberships) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
    }

    /** Must run with the principal's tenant in {@code TenantContext}. */
    @Transactional(readOnly = true)
    public Me describe(StandposPrincipal principal, CurrentDevice device) {
        TenantInfo tenant = tenants.findById(principal.tenantId())
                .map(t -> new TenantInfo(t.getId(), t.getName()))
                .orElse(null);
        UserInfo user = null;
        List<MembershipInfo> roles = List.of();
        if (principal.userId() != null) {
            user = users.findById(principal.userId())
                    .map(u -> new UserInfo(u.getId(), u.getDisplayName(), u.getEmail()))
                    .orElse(null);
            roles = memberships.findAllByUserId(principal.userId()).stream()
                    .map(m -> new MembershipInfo(m.getId(), m.getVenueId(), m.getRole()))
                    .toList();
        }
        DeviceInfo deviceInfo = device == null || !device.tenantId().equals(principal.tenantId())
                ? null
                : new DeviceInfo(device.deviceId(), device.venueId(), device.kind(), device.name());
        return new Me(principal.kind(), tenant, user, roles, deviceInfo, principal.platformAdmin());
    }
}
