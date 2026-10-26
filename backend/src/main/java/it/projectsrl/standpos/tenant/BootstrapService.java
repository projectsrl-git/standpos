package it.projectsrl.standpos.tenant;

import it.projectsrl.standpos.auth.AppUser;
import it.projectsrl.standpos.auth.AppUserRepository;
import it.projectsrl.standpos.auth.AuthLookup;
import it.projectsrl.standpos.auth.Membership;
import it.projectsrl.standpos.auth.MembershipRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.common.Ids;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** Creates a tenant, its first venue and its owner. Idempotent: does nothing if the owner email already exists. */
@Service
public class BootstrapService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    public record Result(boolean created, UUID tenantId, UUID venueId, UUID ownerId) {}

    private final TenantRepository tenants;
    private final VenueRepository venues;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final AuthLookup lookup;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public BootstrapService(TenantRepository tenants, VenueRepository venues, AppUserRepository users,
            MembershipRepository memberships, AuthLookup lookup, PasswordEncoder encoder, TransactionTemplate tx) {
        this.tenants = tenants;
        this.venues = venues;
        this.users = users;
        this.memberships = memberships;
        this.lookup = lookup;
        this.encoder = encoder;
        this.tx = tx;
    }

    public Result bootstrap(BootstrapProperties p) {
        require(p.tenantName(), "STANDPOS_BOOTSTRAP_TENANT_NAME");
        require(p.venueName(), "STANDPOS_BOOTSTRAP_VENUE_NAME");
        require(p.ownerEmail(), "STANDPOS_BOOTSTRAP_OWNER_EMAIL");
        require(p.ownerPassword(), "STANDPOS_BOOTSTRAP_OWNER_PASSWORD");
        if (p.ownerPassword().length() < 10) {
            throw new IllegalArgumentException("STANDPOS_BOOTSTRAP_OWNER_PASSWORD must have at least 10 characters");
        }
        if (StringUtils.hasText(p.ownerPin()) && !p.ownerPin().matches("\\d{4,6}")) {
            throw new IllegalArgumentException("STANDPOS_BOOTSTRAP_OWNER_PIN must be 4 to 6 digits");
        }
        var existing = lookup.findLogin(p.ownerEmail());
        if (existing.isPresent()) {
            log.info("Bootstrap skipped: the owner email already exists");
            return new Result(false, null, null, existing.get().userId());
        }
        UUID tenantId = Ids.newId();
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> {
            tenants.save(new Tenant(tenantId, p.tenantName().trim(), Plan.FREE_ID));
            Venue venue = venues.save(new Venue(p.venueName().trim(), Venue.Kind.PERMANENT));
            String name = StringUtils.hasText(p.ownerName()) ? p.ownerName().trim() : p.ownerEmail().trim();
            AppUser owner = new AppUser(p.ownerEmail(), name);
            owner.setPasswordHash(encoder.encode(p.ownerPassword()));
            if (StringUtils.hasText(p.ownerPin())) {
                owner.setPinHash(encoder.encode(p.ownerPin()));
            }
            users.save(owner);
            memberships.save(new Membership(owner.getId(), null, Role.OWNER));
            log.info("Bootstrap created tenant {}, venue {} and owner {}", tenantId, venue.getId(), owner.getId());
            return new Result(true, tenantId, venue.getId(), owner.getId());
        }));
    }

    private static void require(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required for bootstrap");
        }
    }
}
