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
    private final AppUserRepository users;

    public Authz(MembershipRepository memberships, AppUserRepository users) {
        this.memberships = memberships;
        this.users = users;
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

    /** Owner, or manager or stock clerk of this venue: goods receipts, counts, adjustments. */
    public boolean canManageStock(UUID venueId) {
        return isUser() && venueId != null && myMemberships().stream()
                .anyMatch(m -> m.grants(venueId, Role.MANAGER) || m.grants(venueId, Role.STOCK_CLERK));
    }

    /** Suppliers are tenant-wide: the owner, or anyone who manages stock on some venue. */
    public boolean canManageSuppliers() {
        return isUser() && myMemberships().stream().anyMatch(
                m -> m.getRole() == Role.OWNER || m.getRole() == Role.MANAGER || m.getRole() == Role.STOCK_CLERK);
    }

    /** A platform administrator; the flag is read from the database, not from the session. */
    public boolean isPlatformAdmin() {
        return isUser() && users.findById(requirePrincipal().userId()).map(AppUser::isPlatformAdmin).orElse(false);
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
