package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A cash drawer session on one register: opened by a cashier with a float, closed with the counted cash. At most one
 * shift is open per register (unique index). The printed shift report arrives in Phase 3.
 */
@Entity
@Table(name = "shift")
public class Shift extends TenantOwnedEntity {

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "cashier_id", nullable = false, updatable = false)
    private UUID cashierId;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "opening_float_cents", nullable = false, updatable = false)
    private long openingFloatCents;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "expected_cash_cents")
    private Long expectedCashCents;

    @Column(name = "counted_cash_cents")
    private Long countedCashCents;

    protected Shift() {}

    public Shift(UUID venueId, UUID deviceId, UUID cashierId, Instant openedAt, long openingFloatCents) {
        this.venueId = venueId;
        this.deviceId = deviceId;
        this.cashierId = cashierId;
        this.openedAt = openedAt;
        this.openingFloatCents = openingFloatCents;
    }

    public void close(Instant now, UUID closedBy, long expectedCashCents, long countedCashCents) {
        this.closedAt = now;
        this.closedBy = closedBy;
        this.expectedCashCents = expectedCashCents;
        this.countedCashCents = countedCashCents;
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public UUID getCashierId() {
        return cashierId;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public long getOpeningFloatCents() {
        return openingFloatCents;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public Long getExpectedCashCents() {
        return expectedCashCents;
    }

    public Long getCountedCashCents() {
        return countedCashCents;
    }
}
