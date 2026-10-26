package it.projectsrl.standpos.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base class of every tenant-owned entity. Hibernate's {@link TenantId} fills {@code tenant_id} on insert and adds
 * {@code tenant_id = :current} to every query: the application-level filter in front of PostgreSQL RLS.
 */
@MappedSuperclass
public abstract class TenantOwnedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }
}
