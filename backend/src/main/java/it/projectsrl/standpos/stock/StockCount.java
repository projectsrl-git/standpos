package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A stock count session: lists the tracked products, takes the counted quantities, posts the differences. */
@Entity
@Table(name = "stock_count")
public class StockCount extends TenantOwnedEntity {

    public enum Status {
        DRAFT,
        POSTED
    }

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String notes;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    protected StockCount() {}

    public StockCount(UUID venueId, String notes, UUID createdBy) {
        this.venueId = venueId;
        this.notes = notes;
        this.createdBy = createdBy;
    }

    public void post(Instant now, UUID userId) {
        requireDraft();
        status = Status.POSTED;
        postedAt = now;
        postedBy = userId;
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw ApiException.conflict("stock.count_posted", "A posted stock count cannot change");
        }
    }

    public UUID getVenueId() {
        return venueId;
    }

    public Status getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public UUID getPostedBy() {
        return postedBy;
    }
}
