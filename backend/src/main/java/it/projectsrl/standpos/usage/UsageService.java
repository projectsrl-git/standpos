package it.projectsrl.standpos.usage;

import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.common.Ids;
import it.projectsrl.standpos.tenant.Plan;
import it.projectsrl.standpos.tenant.PlanRepository;
import it.projectsrl.standpos.tenant.Tenant;
import it.projectsrl.standpos.tenant.TenantContext;
import it.projectsrl.standpos.tenant.TenantRepository;
import it.projectsrl.standpos.venue.BusinessDay;
import it.projectsrl.standpos.venue.Venue;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The monthly sales cap (docs/adr/0008-sale-transaction.md). The counter of the sale's calendar month (in the venue's
 * time zone) is incremented inside the sale transaction with an upsert, so it is exact under load and counts every
 * sale, printed or not. From 80% the owner sees a banner; at 100% the venues keep selling until their next day
 * change, then new sales are refused until the next month or until the platform admin raises the cap.
 */
@Service
public class UsageService {

    /** Usage of the current month as the owner's banner and the register show it. */
    public record Status(LocalDate month, int sales, int cap, boolean warning, boolean capReached,
            Instant capReachedAt, Instant blockedFrom, boolean blocked) {}

    static final int WARNING_PERCENT = 80;

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UsageService(JdbcTemplate jdbc, TenantRepository tenants, PlanRepository plans, AuditService audit,
            TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.plans = plans;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Counts one sale at {@code venue}, or refuses it with {@code 409 usage.cap_reached} when the cap was reached
     * before the venue's last day change. Runs in the sale transaction: a refused or rolled-back sale is not counted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSale(Venue venue, Instant now) {
        UUID tenantId = TenantContext.require();
        int cap = cap(tenantId);
        LocalDate month = monthOf(venue, now);
        Map<String, Object> row = jdbc.queryForMap("""
                insert into usage_counter (id, tenant_id, month, sales) values (?, ?, ?, 1)
                on conflict (tenant_id, month) do update set sales = usage_counter.sales + 1, updated_at = now()
                returning id, sales, cap_reached_at, warned_at""", Ids.newId(), tenantId, month);
        UUID counterId = (UUID) row.get("id");
        int sales = ((Number) row.get("sales")).intValue();
        Instant capReachedAt = instant(row.get("cap_reached_at"));
        if (sales - 1 >= cap && capReachedAt != null
                && !now.isBefore(venue.businessDay().nextChangeAfter(capReachedAt))) {
            throw new ApiException(HttpStatus.CONFLICT, "usage.cap_reached",
                    "The monthly sales cap was reached: new sales resume next month")
                    .with("cap", cap);
        }
        if (sales >= cap && capReachedAt == null) {
            jdbc.update("update usage_counter set cap_reached_at = ? where tenant_id = ? and id = ?",
                    now.atOffset(ZoneOffset.UTC), tenantId, counterId);
        }
        if (row.get("warned_at") == null && (long) sales * 100 >= (long) cap * WARNING_PERCENT) {
            jdbc.update("update usage_counter set warned_at = ? where tenant_id = ? and id = ?",
                    now.atOffset(ZoneOffset.UTC), tenantId, counterId);
        }
    }

    /** Usage of the month {@code now} falls in, seen from {@code venue} (its time zone and day change). */
    @Transactional(readOnly = true)
    public Status status(Venue venue, Instant now) {
        return status(venue.businessDay(), monthOf(venue, now), now);
    }

    /** Usage seen by the owner, in the tenant's default time zone (Europe/Rome) when no venue is given. */
    @Transactional(readOnly = true)
    public Status status(BusinessDay day, LocalDate month, Instant now) {
        UUID tenantId = TenantContext.require();
        int cap = cap(tenantId);
        Map<String, Object> row = jdbc.queryForList(
                "select sales, cap_reached_at from usage_counter where tenant_id = ? and month = ?",
                tenantId, month.withDayOfMonth(1)).stream().findFirst().orElse(Map.of());
        int sales = row.get("sales") == null ? 0 : ((Number) row.get("sales")).intValue();
        Instant reachedAt = instant(row.get("cap_reached_at"));
        boolean reached = sales >= cap;
        Instant blockedFrom = reached && reachedAt != null ? day.nextChangeAfter(reachedAt) : null;
        return new Status(month.withDayOfMonth(1), sales, cap, (long) sales * 100 >= (long) cap * WARNING_PERCENT,
                reached, reached ? reachedAt : null, blockedFrom, blockedFrom != null && !now.isBefore(blockedFrom));
    }

    /**
     * Platform admin: sets the tenant's cap override (null goes back to the plan). Months below the new cap are
     * unblocked. Runs in the target tenant; audited there.
     */
    public Status setCapOverride(UUID tenantId, Integer capOverride, BusinessDay day) {
        if (capOverride != null && capOverride <= 0) {
            throw ApiException.badRequest("usage.invalid_cap", "The cap must be positive");
        }
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> {
            Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
            Integer before = tenant.getSalesCapOverride();
            tenant.setSalesCapOverride(capOverride);
            tenants.saveAndFlush(tenant);
            int cap = cap(tenantId);
            jdbc.update("update usage_counter set cap_reached_at = null where tenant_id = ? and sales < ?",
                    tenantId, cap);
            Map<String, Object> beforeData = new LinkedHashMap<>();
            beforeData.put("salesCapOverride", before);
            Map<String, Object> afterData = new LinkedHashMap<>();
            afterData.put("salesCapOverride", capOverride);
            audit.record("tenant.cap_changed", "tenant", tenantId, beforeData, afterData);
            Instant now = clock.instant();
            return status(day, now.atZone(day.zone()).toLocalDate(), now);
        }));
    }

    private int cap(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
        if (tenant.getSalesCapOverride() != null) {
            return tenant.getSalesCapOverride();
        }
        return plans.findById(tenant.getPlanId()).map(Plan::getSalesPerMonth).orElseThrow();
    }

    private static LocalDate monthOf(Venue venue, Instant now) {
        return now.atZone(venue.businessDay().zone()).toLocalDate().withDayOfMonth(1);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Timestamp t -> t.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            case Instant i -> i;
            default -> throw new IllegalStateException("Unexpected timestamp " + value.getClass());
        };
    }
}
