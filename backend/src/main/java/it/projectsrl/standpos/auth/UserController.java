package it.projectsrl.standpos.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Users and memberships: owner only. */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record CreateUserRequest(
            @NotBlank @Size(max = 80) String displayName,
            @Email @Size(max = 254) String email,
            @Size(min = 10, max = 200) String password,
            @Pattern(regexp = "\\d{4,6}") String pin,
            UUID venueId,
            @NotNull Role role) {}

    record UpdateUserRequest(@Size(max = 80) String displayName, @Pattern(regexp = "\\d{4,6}") String pin,
            boolean clearPin) {}

    record MembershipRequest(UUID venueId, @NotNull Role role) {}

    record UnlockRequest(Boolean account, Boolean pin) {}

    record MembershipResponse(UUID id, UUID venueId, Role role) {}

    record UserResponse(UUID id, String displayName, String email, boolean hasPassword, boolean hasPin,
            List<MembershipResponse> memberships, Instant lockedUntil, Instant pinLockedUntil) {

        static UserResponse of(UserService.UserView v) {
            return new UserResponse(v.id(), v.displayName(), v.email(), v.hasPassword(), v.hasPin(),
                    v.memberships().stream()
                            .map(m -> new MembershipResponse(m.getId(), m.getVenueId(), m.getRole()))
                            .toList(),
                    v.lockedUntil(), v.pinLockedUntil());
        }
    }

    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("@authz.isOwner()")
    List<UserResponse> list() {
        return users.list().stream().map(UserResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.isOwner()")
    UserResponse create(@Valid @RequestBody CreateUserRequest r) {
        return UserResponse.of(users.create(
                new UserService.NewUser(r.displayName(), r.email(), r.password(), r.pin(), r.venueId(), r.role())));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("@authz.isOwner()")
    UserResponse update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest r) {
        return UserResponse.of(users.update(userId, r.displayName(), r.pin(), r.clearPin()));
    }

    /** Clears the account lockout and/or the PIN lockout before it expires; both when the body says nothing. */
    @PostMapping("/{userId}/unlock")
    @PreAuthorize("@authz.isOwner()")
    UserResponse unlock(@PathVariable UUID userId, @RequestBody(required = false) UnlockRequest r) {
        boolean account = r == null || r.account() == null || r.account();
        boolean pin = r == null || r.pin() == null || r.pin();
        return UserResponse.of(users.unlock(userId, account, pin));
    }

    @PostMapping("/{userId}/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.isOwner()")
    UserResponse addMembership(@PathVariable UUID userId, @Valid @RequestBody MembershipRequest r) {
        return UserResponse.of(users.addMembership(userId, r.venueId(), r.role()));
    }

    @DeleteMapping("/{userId}/memberships/{membershipId}")
    @PreAuthorize("@authz.isOwner()")
    ResponseEntity<UserResponse> removeMembership(@PathVariable UUID userId, @PathVariable UUID membershipId) {
        UserService.UserView remaining = users.removeMembership(userId, membershipId);
        return remaining == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(UserResponse.of(remaining));
    }
}
