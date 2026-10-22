package it.projectsrl.standpos.device;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An enrolled station: register, kitchen screen or print agent. Only the SHA-256 of its token is stored. */
@Entity
@Table(name = "device")
public class Device extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DeviceKind kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected Device() {}

    public Device(UUID venueId, DeviceKind kind, String name, byte[] tokenHash, UUID createdBy) {
        this.venueId = venueId;
        this.kind = kind;
        this.name = name;
        this.tokenHash = tokenHash.clone();
        this.createdBy = createdBy;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public void seen(Instant now) {
        lastSeenAt = now;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public DeviceKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
