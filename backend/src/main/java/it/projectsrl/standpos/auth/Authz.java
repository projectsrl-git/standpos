package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.device.DeviceKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Authorisation rules used in {@code @PreAuthorize("@authz...")}. Memberships are read on each request (cached for
 * the request), so a removed role takes effect immediately, not at the next login.
 */
@Component("authz")
public class Authz {

    private static final String CACHE = Authz.class.getName() + ".memberships";

    private final MembershipRepository memberships;

    public Authz(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public static Optional<StandposPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StandposPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public static StandposPrincipal requirePrincipal() {
        return principal().orElseThrow(() -> new IllegalStateException("No StandPOS principal"));
    }

    /** An owner or manager logged in with email and password. */
    public boolean isUser() {
        return principal().filter(p -> p.kind() == StandposPrincipal.Kind.USER).isPresent();
    }

    public boolean isOwner() {
        return isUser() && myMemberships().stream().anyMatch(m -> m.getRole() == Role.OWNER);
    }

    /** Owner of the tenant, or manager of this venue. */
    public boolean canManageVenue(UUID venueId) {
        return isUser() && venueId != null && myMemberships().stream().anyMatch(m -> m.grants(venueId, Role.MANAGER));
    }

    /** Any user holding a role in the tenant. */
    public boolean hasAnyMembership() {
        return isUser() && !myMemberships().isEmpty();
    }

    /** The request comes from an enrolled, non-revoked register. */
    public boolean isRegisterDevice() {
        return CurrentDevice.current().filter(d -> d.kind() == DeviceKind.REGISTER).isPresent();
    }

    /** A cashier logged in with a PIN on this register, still holding a role on its venue. */
    public boolean isCashier() {
        Optional<StandposPrincipal> principal = principal().filter(p -> p.kind() == StandposPrincipal.Kind.CASHIER);
        if (principal.isEmpty() || !isRegisterDevice()) {
            return false;
        }
        UUID venueId = principal.get().venueId();
        return myMemberships().stream().anyMatch(m -> m.getRole() == Role.OWNER || venueId.equals(m.getVenueId()));
    }

    @SuppressWarnings("unchecked")
    private List<Membership> myMemberships() {
        StandposPrincipal principal = requirePrincipal();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object cached = attributes.getAttribute(CACHE, RequestAttributes.SCOPE_REQUEST);
            if (cached != null) {
                return (List<Membership>) cached;
            }
        }
        List<Membership> result = memberships.findAllByUserId(principal.userId());
        if (attributes != null) {
            attributes.setAttribute(CACHE, result, RequestAttributes.SCOPE_REQUEST);
        }
        return result;
    }
}
