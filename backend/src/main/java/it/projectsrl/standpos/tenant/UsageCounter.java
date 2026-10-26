package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** Sales counted for one tenant in one calendar month. The counting logic arrives in Phase 1. */
@Entity
@Table(name = "usage_counter")
public class UsageCounter extends TenantOwnedEntity {

    @Column(nullable = false)
    private LocalDate month;

    @Column(nullable = false)
    private int sales;

    @Column(name = "warned_at")
    private Instant warnedAt;

    protected UsageCounter() {}

    public UsageCounter(LocalDate month) {
        this.month = month.withDayOfMonth(1);
    }

    public LocalDate getMonth() {
        return month;
    }

    public int getSales() {
        return sales;
    }

    public Instant getWarnedAt() {
        return warnedAt;
    }
}
