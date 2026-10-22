package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A usage plan. v1 has one, seeded by migration: free, 2,000 sales per tenant per month. */
@Entity
@Immutable
@Table(name = "plan")
public class Plan extends BaseEntity {

    public static final UUID FREE_ID = UUID.fromString("01920000-0000-7000-8000-000000000001");

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "sales_per_month", nullable = false)
    private int salesPerMonth;

    protected Plan() {}

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getSalesPerMonth() {
        return salesPerMonth;
    }
}
