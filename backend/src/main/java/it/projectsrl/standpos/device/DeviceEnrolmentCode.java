package it.projectsrl.standpos.device;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A one-time code a manager hands to a device so it can enrol. Claimed through {@code auth.claim_enrolment_code}. */
@Entity
@Table(name = "device_enrolment_code")
public class DeviceEnrolmentCode extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DeviceKind kind;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected DeviceEnrolmentCode() {}

    public DeviceEnrolmentCode(UUID venueId, DeviceKind kind, String code, Instant expiresAt, UUID createdBy) {
        this.venueId = venueId;
        this.kind = kind;
        this.code = code;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public void linkDevice(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public DeviceKind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
