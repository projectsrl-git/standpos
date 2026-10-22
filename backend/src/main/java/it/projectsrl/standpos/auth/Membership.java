package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** A role a user holds in a tenant: tenant-wide for OWNER ({@code venueId == null}), otherwise on one venue. */
@Entity
@Table(name = "membership")
public class Membership extends TenantOwnedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "venue_id", updatable = false)
    private UUID venueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    protected Membership() {}

    public Membership(UUID userId, UUID venueId, Role role) {
        if (role == Role.PLATFORM_ADMIN) {
            throw new IllegalArgumentException("PLATFORM_ADMIN is not a membership");
        }
        if (role.isTenantWide() != (venueId == null)) {
            throw new IllegalArgumentException(role + (role.isTenantWide() ? " is tenant-wide" : " needs a venue"));
        }
        this.userId = userId;
        this.venueId = venueId;
        this.role = role;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public Role getRole() {
        return role;
    }

    /** True when this membership grants {@code role} on {@code venueId} (OWNER grants every venue). */
    public boolean grants(UUID venueId, Role role) {
        return this.role == Role.OWNER || (this.role == role && venueId != null && venueId.equals(this.venueId));
    }
}
