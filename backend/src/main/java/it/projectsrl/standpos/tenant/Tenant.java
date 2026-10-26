package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** One organisation. Its id is the tenant id; RLS shows a tenant only its own row. */
@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "sales_cap_override")
    private Integer salesCapOverride;

    protected Tenant() {}

    public Tenant(UUID id, String name, UUID planId) {
        assignId(id);
        this.name = name;
        this.planId = planId;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getPlanId() {
        return planId;
    }

    public Integer getSalesCapOverride() {
        return salesCapOverride;
    }

    /** Per-tenant cap that replaces the plan's sales per month; null means the plan's. */
    public void setSalesCapOverride(Integer salesCapOverride) {
        this.salesCapOverride = salesCapOverride;
    }
}
