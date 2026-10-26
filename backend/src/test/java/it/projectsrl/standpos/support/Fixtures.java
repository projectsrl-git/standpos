package it.projectsrl.standpos.support;

import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.auth.UserService;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceService;
import it.projectsrl.standpos.tenant.BootstrapProperties;
import it.projectsrl.standpos.tenant.BootstrapService;
import it.projectsrl.standpos.tenant.TenantContext;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates test data through the application's own services, each in its own tenant. */
@TestComponent
public class Fixtures {

    public static final String OWNER_PASSWORD = "owner-password-123";
    public static final String PIN = "2468";

    public record TenantFixture(UUID tenantId, UUID venueId, UUID ownerId, String ownerEmail) {}

    private final BootstrapService bootstrap;
    private final VenueRepository venues;
    private final UserService users;
    private final DeviceService devices;
    private final TransactionTemplate tx;

    Fixtures(BootstrapService bootstrap, VenueRepository venues, UserService users, DeviceService devices,
            TransactionTemplate tx) {
        this.bootstrap = bootstrap;
        this.venues = venues;
        this.users = users;
        this.devices = devices;
        this.tx = tx;
    }

    /** A tenant with one venue and an owner (password {@link #OWNER_PASSWORD}, PIN {@link #PIN}). */
    public TenantFixture tenant(String name) {
        String email = "owner-" + UUID.randomUUID() + "@standpos.test";
        BootstrapService.Result result = bootstrap.bootstrap(new BootstrapProperties(
                false, name, name + " bar", "Owner " + name, email, OWNER_PASSWORD, PIN));
        return new TenantFixture(result.tenantId(), result.venueId(), result.ownerId(), email);
    }

    public <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> action.get()));
    }

    public UUID venue(TenantFixture tenant, String name) {
        return inTenant(tenant.tenantId(), () -> venues.save(new Venue(name, Venue.Kind.EVENT)).getId());
    }

    public UUID user(TenantFixture tenant, String name, String email, String password, String pin, UUID venueId,
            Role role) {
        return TenantContext.callAs(tenant.tenantId(),
                () -> users.create(new UserService.NewUser(name, email, password, pin, venueId, role)).id());
    }

    /** Enrols a device and returns its token. */
    public String enrol(TenantFixture tenant, UUID venueId, DeviceKind kind) {
        String code = TenantContext.callAs(tenant.tenantId(),
                () -> devices.createEnrolmentCode(venueId, kind, tenant.ownerId()).getCode());
        return devices.enrol(code, kind + " test", "fixtures-" + UUID.randomUUID(),
                kind == DeviceKind.PRINT_AGENT ? DeviceService.AGENT_KINDS : DeviceService.BROWSER_KINDS).token();
    }
}
