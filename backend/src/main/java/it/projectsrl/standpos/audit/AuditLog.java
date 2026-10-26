package it.projectsrl.standpos.audit;

import it.projectsrl.standpos.common.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** Append-only record of a sensitive change. {@code before}/{@code after} are JSON documents. */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLog {

    @Id
    private UUID id = Ids.newId();

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "before_data", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String beforeData;

    @Column(name = "after_data", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String afterData;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {}

    AuditLog(UUID userId, UUID deviceId, String action, String entityType, UUID entityId, String beforeData,
            String afterData) {
        this.userId = userId;
        this.deviceId = deviceId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeData = beforeData;
        this.afterData = afterData;
    }

    @PrePersist
    void onPersist() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getBeforeData() {
        return beforeData;
    }

    public String getAfterData() {
        return afterData;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
