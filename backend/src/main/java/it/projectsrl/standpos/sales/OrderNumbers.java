package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily order number: one counter row per venue and business day, incremented with an upsert inside the sale
 * transaction. The row lock serialises concurrent sales of the venue for the instant of the increment only, and a
 * rolled-back sale gives its number back.
 */
@Component
class OrderNumbers {

    private final JdbcTemplate jdbc;

    OrderNumbers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    int next(UUID venueId, LocalDate businessDate) {
        Integer number = jdbc.queryForObject("""
                insert into order_day_counter (tenant_id, venue_id, business_date, last_number) values (?, ?, ?, 1)
                on conflict (tenant_id, venue_id, business_date)
                do update set last_number = order_day_counter.last_number + 1, updated_at = now()
                returning last_number""", Integer.class, TenantContext.require(), venueId, businessDate);
        return number == null ? 1 : number;
    }
}
