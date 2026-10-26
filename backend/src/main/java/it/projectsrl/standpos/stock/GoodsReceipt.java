package it.projectsrl.standpos.stock;

import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Goods received at a venue. A draft is edited freely; posting writes the receipt movements and freezes it. */
@Entity
@Table(name = "goods_receipt")
public class GoodsReceipt extends TenantOwnedEntity {

    public enum Status {
        DRAFT,
        POSTED
    }

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "document_ref")
    private String documentRef;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    protected GoodsReceipt() {}

    public GoodsReceipt(UUID venueId, UUID createdBy) {
        this.venueId = venueId;
        this.createdBy = createdBy;
    }

    public void update(UUID supplierId, String documentRef, LocalDate receivedOn, String notes) {
        requireDraft();
        this.supplierId = supplierId;
        this.documentRef = documentRef;
        this.receivedOn = receivedOn;
        this.notes = notes;
    }

    public void post(Instant now, UUID userId) {
        requireDraft();
        status = Status.POSTED;
        postedAt = now;
        postedBy = userId;
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw ApiException.conflict(
                    "stock.receipt_posted", "A posted goods receipt cannot change");
        }
    }

    public UUID getVenueId() {
        return venueId;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public String getDocumentRef() {
        return documentRef;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public String getNotes() {
        return notes;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public UUID getPostedBy() {
        return postedBy;
    }
}
