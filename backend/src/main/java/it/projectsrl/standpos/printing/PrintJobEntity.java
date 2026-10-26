package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import it.projectsrl.standpos.print.protocol.PrintJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A print job in the server-side queue ({@code print_job}). QUEUED until delivered to the agent, SENT while the agent
 * prints, then PRINTED or FAILED from the agent's acknowledgement. The document is the protocol JSON.
 */
@Entity
@Table(name = "print_job")
public class PrintJobEntity extends TenantOwnedEntity {

    public enum Status {
        QUEUED,
        SENT,
        PRINTED,
        FAILED
    }

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "printer_id", nullable = false, updatable = false)
    private UUID printerId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PrintJob.Kind kind;

    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected PrintJobEntity() {}

    public PrintJobEntity(UUID venueId, UUID printerId, UUID orderId, PrintJob.Kind kind, String document,
            UUID createdBy) {
        this.venueId = venueId;
        this.printerId = printerId;
        this.orderId = orderId;
        this.kind = kind;
        this.document = document;
        this.createdBy = createdBy;
    }

    public void sent(Instant now) {
        if (status == Status.QUEUED || status == Status.SENT) {
            status = Status.SENT;
            sentAt = now;
            attempts++;
        }
    }

    public void printed(Instant now) {
        status = Status.PRINTED;
        finishedAt = now;
        lastError = null;
    }

    public void failed(Instant now, String error) {
        status = Status.FAILED;
        finishedAt = now;
        lastError = error;
    }

    public boolean isPending() {
        return status == Status.QUEUED || status == Status.SENT;
    }

    public UUID getVenueId() {
        return venueId;
    }

    public UUID getPrinterId() {
        return printerId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public PrintJob.Kind getKind() {
        return kind;
    }

    public String getDocument() {
        return document;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
