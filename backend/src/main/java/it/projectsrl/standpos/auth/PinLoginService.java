package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cashier PIN login. Accepted only from an enrolled REGISTER device, for a user who holds a role on that device's
 * venue (or is the tenant owner). Repeated failures lock the user's PIN.
 */
@Service
public class PinLoginService {

    private static final Logger log = LoggerFactory.getLogger(PinLoginService.class);

    /** Users the register may offer on its PIN page. */
    public record Cashier(UUID userId, String displayName) {}

    private enum Outcome {
        OK,
        UNKNOWN,
        NOT_ALLOWED,
        WRONG,
        LOCKED
    }

    private record Attempt(Outcome outcome, String displayName) {}

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final SecurityProperties properties;
    private final Clock clock;

    public PinLoginService(AppUserRepository users, MembershipRepository memberships, PasswordEncoder encoder,
            TransactionTemplate tx, SecurityProperties properties, Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Cashier> cashiers(CurrentDevice device) {
        requireRegister(device);
        return TenantContext.callAs(device.tenantId(), () -> tx.execute(status -> {
            var allowed = memberships.findAll().stream()
                    .filter(m -> m.getRole() == Role.OWNER || device.venueId().equals(m.getVenueId()))
                    .map(Membership::getUserId)
                    .distinct()
                    .toList();
            return users.findAllById(allowed).stream()
                    .filter(u -> u.hasPin() && u.getStatus() == AppUser.Status.ACTIVE)
                    .map(u -> new Cashier(u.getId(), u.getDisplayName()))
                    .sorted((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()))
                    .toList();
        }));
    }

    public StandposPrincipal login(CurrentDevice device, UUID userId, String pin) {
        requireRegister(device);
        // The attempt commits before any error is thrown, so failure counts survive.
        Attempt attempt = TenantContext.callAs(device.tenantId(), () -> tx.execute(status -> verify(userId, pin,
                m -> m.getRole() == Role.OWNER || device.venueId().equals(m.getVenueId()))));
        return switch (attempt.outcome()) {
            case OK -> StandposPrincipal.cashier(
                    device.tenantId(), userId, attempt.displayName(), device.deviceId(), device.venueId());
            case UNKNOWN, WRONG -> throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.invalid_pin", "Invalid PIN");
            case NOT_ALLOWED -> throw new ApiException(
                    HttpStatus.FORBIDDEN, "auth.pin_not_allowed", "The user has no role on this device's venue");
            case LOCKED -> throw lockedPin();
        };
    }

    /** Users who may approve a void on this register: owners and the venue's managers, with a PIN. */
    public List<Cashier> approvers(CurrentDevice device) {
        requireRegister(device);
        return TenantContext.callAs(device.tenantId(), () -> tx.execute(status -> {
            var allowed = memberships.findAll().stream()
                    .filter(m -> m.grants(device.venueId(), Role.MANAGER))
                    .map(Membership::getUserId)
                    .distinct()
                    .toList();
            return users.findAllById(allowed).stream()
                    .filter(u -> u.hasPin() && u.getStatus() == AppUser.Status.ACTIVE)
                    .map(u -> new Cashier(u.getId(), u.getDisplayName()))
                    .sorted((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()))
                    .toList();
        }));
    }

    /**
     * Verifies the PIN of a manager approving an action on the register (a void), with the same lockout as the PIN
     * login. The cashier's session is untouched. Must be called outside a transaction: the attempt commits first.
     */
    public void verifyApprover(CurrentDevice device, UUID userId, String pin) {
        requireRegister(device);
        Attempt attempt = TenantContext.callAs(device.tenantId(), () -> tx.execute(status -> verify(userId, pin,
                m -> m.grants(device.venueId(), Role.MANAGER))));
        switch (attempt.outcome()) {
            case OK -> {}
            case UNKNOWN, WRONG -> throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.invalid_pin", "Invalid PIN");
            case NOT_ALLOWED -> throw new ApiException(
                    HttpStatus.FORBIDDEN, "auth.not_approver", "The user is not a manager of this venue");
            case LOCKED -> throw lockedPin();
        }
    }

    private static ApiException lockedPin() {
        return new ApiException(HttpStatus.LOCKED, "auth.pin_locked", "Too many wrong PINs, try again later");
    }

    private Attempt verify(UUID userId, String pin, Predicate<Membership> allowedBy) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || !user.hasPin() || user.getStatus() != AppUser.Status.ACTIVE) {
            return new Attempt(Outcome.UNKNOWN, null);
        }
        boolean allowed = memberships.findAllByUserId(userId).stream().anyMatch(allowedBy);
        if (!allowed) {
            return new Attempt(Outcome.NOT_ALLOWED, null);
        }
        var now = clock.instant();
        if (user.isPinLocked(now)) {
            return new Attempt(Outcome.LOCKED, null);
        }
        if (!encoder.matches(pin, user.getPinHash())) {
            boolean lockedNow = user.recordPinFailure(now, properties.maxPinFailures(), properties.pinLockDuration());
            if (lockedNow) {
                log.warn("PIN of user {} locked after repeated failures", userId);
                return new Attempt(Outcome.LOCKED, null);
            }
            return new Attempt(Outcome.WRONG, null);
        }
        user.recordPinSuccess();
        return new Attempt(Outcome.OK, user.getDisplayName());
    }

    private static void requireRegister(CurrentDevice device) {
        if (device == null || device.kind() != DeviceKind.REGISTER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "auth.register_required", "Only an enrolled register may do this");
        }
    }
}
