package it.projectsrl.standpos.usage;

import it.projectsrl.standpos.venue.BusinessDay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's usage banner, and the minimal platform-admin endpoint to raise a tenant's cap (the full console is
 * Phase 4). Owner-facing dates use Europe/Rome with a 04:00 day change, the venue defaults.
 */
@RestController
class UsageController {

    static final BusinessDay TENANT_DAY = new BusinessDay(ZoneId.of("Europe/Rome"), LocalTime.of(4, 0));

    record CapRequest(@Positive Integer salesCapOverride) {}

    private final UsageService usage;
    private final Clock clock;

    UsageController(UsageService usage, Clock clock) {
        this.usage = usage;
        this.clock = clock;
    }

    @GetMapping("/api/v1/usage")
    @PreAuthorize("@authz.isOwner()")
    UsageService.Status current() {
        Instant now = clock.instant();
        return usage.status(TENANT_DAY, now.atZone(TENANT_DAY.zone()).toLocalDate(), now);
    }

    /** Platform admin: sets (or with null, removes) the tenant's cap override. Audited in that tenant. */
    @PutMapping("/api/v1/admin/tenants/{tenantId}/sales-cap")
    @PreAuthorize("@authz.isPlatformAdmin()")
    UsageService.Status setCap(@PathVariable UUID tenantId, @Valid @RequestBody CapRequest r) {
        return usage.setCapOverride(tenantId, r.salesCapOverride(), TENANT_DAY);
    }
}
