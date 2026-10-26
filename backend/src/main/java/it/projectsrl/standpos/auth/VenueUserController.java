package it.projectsrl.standpos.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lock state of the people working at a venue, and early PIN unlock, for the venue's managers (owners use
 * {@code /api/v1/users}, which can also unlock passwords). Lock times in the past mean the lock has expired.
 */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/users")
class VenueUserController {

    record LockResponse(UUID userId, String displayName, List<Role> roles, Instant lockedUntil,
            Instant pinLockedUntil) {

        static LockResponse of(UserService.UserView v) {
            return new LockResponse(v.id(), v.displayName(), v.memberships().stream().map(Membership::getRole).toList(),
                    v.lockedUntil(), v.pinLockedUntil());
        }
    }

    private final UserService users;

    VenueUserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    List<LockResponse> list(@PathVariable UUID venueId) {
        return users.venueUsers(venueId).stream().map(LockResponse::of).toList();
    }

    @PostMapping("/{userId}/unlock-pin")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    LockResponse unlockPin(@PathVariable UUID venueId, @PathVariable UUID userId) {
        return LockResponse.of(users.unlockPin(venueId, userId));
    }
}
