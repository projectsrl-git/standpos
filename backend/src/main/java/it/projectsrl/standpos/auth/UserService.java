package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Users and memberships of the current tenant. Membership changes are audited as role changes. */
@Service
public class UserService {

    public record UserView(UUID id, String displayName, String email, boolean hasPassword, boolean hasPin,
            List<Membership> memberships, Instant lockedUntil, Instant pinLockedUntil) {}

    public record NewUser(String displayName, String email, String password, String pin, UUID venueId, Role role) {}

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final VenueRepository venues;
    private final AuthLookup lookup;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(AppUserRepository users, MembershipRepository memberships, VenueRepository venues,
            AuthLookup lookup, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.memberships = memberships;
        this.venues = venues;
        this.lookup = lookup;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        Map<UUID, List<Membership>> byUser = new LinkedHashMap<>();
        memberships.findAll().forEach(m -> byUser.computeIfAbsent(m.getUserId(), k -> new java.util.ArrayList<>()).add(m));
        return users.findAllByOrderByDisplayName().stream()
                .map(u -> view(u, byUser.getOrDefault(u.getId(), List.of())))
                .toList();
    }

    /**
     * Creates a user with a first membership. When the email already belongs to a user of another tenant, that
     * person is added to this tenant instead; their name and credentials are left untouched.
     */
    @Transactional
    public UserView create(NewUser request) {
        checkVenue(request.venueId(), request.role());
        String email = request.email() == null || request.email().isBlank() ? null : request.email().trim().toLowerCase();
        UUID userId = email == null ? null : lookup.findLogin(email).map(AuthLookup.LoginRow::userId).orElse(null);
        if (userId == null) {
            AppUser user = new AppUser(email, request.displayName().trim());
            if (request.password() != null && !request.password().isBlank()) {
                user.setPasswordHash(encoder.encode(request.password()));
            }
            if (request.pin() != null && !request.pin().isBlank()) {
                user.setPinHash(encoder.encode(request.pin()));
            }
            users.saveAndFlush(user);
            userId = user.getId();
        }
        insertMembership(userId, request.venueId(), request.role());
        return get(userId);
    }

    @Transactional
    public UserView update(UUID userId, String displayName, String pin, boolean clearPin) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (displayName != null && !displayName.isBlank()) {
            user.rename(displayName.trim());
        }
        if (clearPin) {
            user.setPinHash(null);
        } else if (pin != null && !pin.isBlank()) {
            user.setPinHash(encoder.encode(pin));
        }
        return get(userId);
    }

    /** Adds a role to a user already in this tenant (users of other tenants are invisible here, by RLS). */
    @Transactional
    public UserView addMembership(UUID userId, UUID venueId, Role role) {
        users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        insertMembership(userId, venueId, role);
        return get(userId);
    }

    private void insertMembership(UUID userId, UUID venueId, Role role) {
        checkVenue(venueId, role);
        boolean exists = memberships.findAllByUserId(userId).stream()
                .anyMatch(m -> m.getRole() == role && java.util.Objects.equals(m.getVenueId(), venueId));
        if (exists) {
            throw ApiException.conflict("user.membership_exists", "The user already holds this role");
        }
        Membership membership = memberships.saveAndFlush(new Membership(userId, venueId, role));
        audit.record("membership.created", "membership", membership.getId(), null, describe(membership));
    }

    @Transactional
    public UserView removeMembership(UUID userId, UUID membershipId) {
        Membership membership = memberships.findById(membershipId)
                .filter(m -> m.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Membership"));
        if (membership.getRole() == Role.OWNER && memberships.countByRole(Role.OWNER) <= 1) {
            throw ApiException.conflict("user.last_owner", "A tenant must keep at least one owner");
        }
        memberships.delete(membership);
        audit.record("membership.deleted", "membership", membership.getId(), describe(membership), null);
        return memberships.findAllByUserId(userId).isEmpty() ? null : get(userId);
    }

    /** Owner: clears the password lockout and/or the PIN lockout before the 15 minutes expire. Audited. */
    @Transactional
    public UserView unlock(UUID userId, boolean account, boolean pin) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        recordUnlock(user, account, pin);
        return get(userId);
    }

    /** Users holding a role on the venue, with their lock state, for the venue's managers. */
    @Transactional(readOnly = true)
    public List<UserView> venueUsers(UUID venueId) {
        venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
        Map<UUID, List<Membership>> byUser = new LinkedHashMap<>();
        memberships.findAllByVenueId(venueId)
                .forEach(m -> byUser.computeIfAbsent(m.getUserId(), k -> new java.util.ArrayList<>()).add(m));
        return users.findAllById(byUser.keySet()).stream()
                .sorted((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName()))
                .map(u -> view(u, byUser.get(u.getId())))
                .toList();
    }

    /** Venue manager: clears the PIN lockout of a user who holds a role on that venue. Audited. */
    @Transactional
    public UserView unlockPin(UUID venueId, UUID userId) {
        boolean onVenue = memberships.findAllByUserId(userId).stream().anyMatch(m -> venueId.equals(m.getVenueId()));
        if (!onVenue) {
            throw ApiException.notFound("User");
        }
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        recordUnlock(user, false, true);
        return view(user, memberships.findAllByUserId(userId).stream()
                .filter(m -> venueId.equals(m.getVenueId()))
                .toList());
    }

    private void recordUnlock(AppUser user, boolean account, boolean pin) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("lockedUntil", user.getLockedUntil());
        before.put("pinLockedUntil", user.getPinLockedUntil());
        if (account) {
            user.unlockAccount();
        }
        if (pin) {
            user.unlockPin();
        }
        users.saveAndFlush(user);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("account", account);
        after.put("pin", pin);
        audit.record("user.unlocked", "app_user", user.getId(), before, after);
    }

    private UserView get(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        return view(user, memberships.findAllByUserId(userId));
    }

    private void checkVenue(UUID venueId, Role role) {
        if (role == null || role == Role.PLATFORM_ADMIN) {
            throw ApiException.badRequest("user.invalid_role", "Role not assignable");
        }
        if (role.isTenantWide() != (venueId == null)) {
            throw ApiException.badRequest("user.invalid_role",
                    role.isTenantWide() ? "OWNER is tenant-wide: no venue" : role + " needs a venue");
        }
        if (venueId != null && venues.findById(venueId).isEmpty()) {
            throw ApiException.notFound("Venue");
        }
    }

    private static Map<String, Object> describe(Membership m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", m.getUserId());
        data.put("venueId", m.getVenueId());
        data.put("role", m.getRole());
        return data;
    }

    private static UserView view(AppUser user, List<Membership> memberships) {
        return new UserView(user.getId(), user.getDisplayName(), user.getEmail(), user.getPasswordHash() != null,
                user.hasPin(), memberships, user.getLockedUntil(), user.getPinLockedUntil());
    }
}
