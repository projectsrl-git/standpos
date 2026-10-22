package it.projectsrl.standpos.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.projectsrl.standpos.audit.AuditLogRepository;
import it.projectsrl.standpos.audit.AuditService;
import it.projectsrl.standpos.auth.AppUser;
import it.projectsrl.standpos.auth.AppUserRepository;
import it.projectsrl.standpos.auth.MembershipRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.catalog.Category;
import it.projectsrl.standpos.catalog.CategoryRepository;
import it.projectsrl.standpos.catalog.Product;
import it.projectsrl.standpos.catalog.ProductRepository;
import it.projectsrl.standpos.catalog.Route;
import it.projectsrl.standpos.catalog.RouteRepository;
import it.projectsrl.standpos.common.TenantOwnedEntity;
import it.projectsrl.standpos.device.DeviceEnrolmentCodeRepository;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import it.projectsrl.standpos.venue.Area;
import it.projectsrl.standpos.venue.AreaRepository;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance criterion 2: with two tenants holding data in every tenant-owned table, a connection as
 * {@code standpos_app} with tenant A set sees only A's rows, through repositories and through native
 * {@code SELECT *}; with no tenant set it sees zero rows.
 */
class RlsIsolationIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TenantRepository tenants;

    @Autowired
    UsageCounterRepository usageCounters;

    @Autowired
    VenueRepository venues;

    @Autowired
    AreaRepository areas;

    @Autowired
    AppUserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    DeviceRepository devices;

    @Autowired
    DeviceEnrolmentCodeRepository codes;

    @Autowired
    RouteRepository routes;

    @Autowired
    CategoryRepository categories;

    @Autowired
    ProductRepository products;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    AuditService audit;

    TenantFixture a;
    TenantFixture b;

    @BeforeEach
    void twoTenantsWithData() {
        a = fixtures.tenant("Tenant A");
        b = fixtures.tenant("Tenant B");
        for (TenantFixture t : List.of(a, b)) {
            fixtures.user(t, "Cashier", null, null, "1357", t.venueId(), Role.CASHIER);
            fixtures.enrol(t, t.venueId(), DeviceKind.REGISTER); // device + used enrolment code
            fixtures.inTenant(t.tenantId(), () -> {
                usageCounters.save(new UsageCounter(LocalDate.now()));
                areas.save(new Area(t.venueId(), "Cucina"));
                Route route = routes.save(new Route(t.venueId(), "Bar", 0));
                Category category = categories.save(new Category(t.venueId(), "Bibite", "#1E88E5", 0, route.getId()));
                products.saveAndFlush(new Product(t.venueId(), category.getId(), "Acqua", 100));
                audit.record("test.seeded", "tenant", t.tenantId(), null, Map.of("ok", true));
                return null;
            });
        }
    }

    @Test
    void runsAsTheRestrictedApplicationRole() {
        Map<String, Object> role = jdbc.queryForMap(
                "select current_user as name, r.rolsuper, r.rolbypassrls from pg_roles r where r.rolname = current_user");
        assertThat(role).containsEntry("name", "standpos_app")
                .containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false);
        Integer owned = jdbc.queryForObject(
                "select count(*) from pg_tables where schemaname = 'public' and tableowner = current_user",
                Integer.class);
        assertThat(owned).isZero();
    }

    @Test
    void repositoriesReturnOnlyTheCurrentTenantsRows() {
        List<JpaRepository<? extends TenantOwnedEntity, UUID>> tenantOwned = List.of(
                usageCounters, venues, areas, memberships, devices, codes, routes, categories, products);
        asTenant(a.tenantId(), () -> {
            for (JpaRepository<? extends TenantOwnedEntity, UUID> repository : tenantOwned) {
                List<? extends TenantOwnedEntity> rows = repository.findAll();
                assertThat(rows).as(repository.toString()).isNotEmpty()
                        .allSatisfy(row -> assertThat(row.getTenantId()).isEqualTo(a.tenantId()));
            }
            assertThat(auditLogs.findAll()).isNotEmpty()
                    .allSatisfy(row -> assertThat(row.getTenantId()).isEqualTo(a.tenantId()));
            assertThat(tenants.findAll()).extracting(Tenant::getId).containsExactly(a.tenantId());
            assertThat(users.findAll()).extracting(AppUser::getId).contains(a.ownerId()).doesNotContain(b.ownerId());
            assertThat(users.findById(b.ownerId())).isEmpty();
            assertThat(venues.findById(b.venueId())).isEmpty();
            return null;
        });
    }

    @Test
    void nativeSelectStarReturnsOnlyTheCurrentTenantsRows() {
        List<String> tables = tenantOwnedTables();
        assertThat(tables).contains("usage_counter", "venue", "area", "membership", "device", "device_enrolment_code",
                "route", "category", "product", "audit_log");
        asTenant(a.tenantId(), () -> {
            for (String table : tables) {
                List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table);
                assertThat(rows).as(table).isNotEmpty()
                        .allSatisfy(row -> assertThat(row.get("tenant_id")).as(table).isEqualTo(a.tenantId()));
            }
            assertThat(jdbc.queryForList("select * from tenant"))
                    .extracting(row -> row.get("id")).containsExactly(a.tenantId());
            assertThat(jdbc.queryForList("select id from app_user", UUID.class))
                    .contains(a.ownerId()).doesNotContain(b.ownerId());
            return null;
        });
    }

    @Test
    void withNoTenantEveryTenantOwnedTableIsEmpty() {
        List<String> tables = tenantOwnedTables();
        tx.execute(status -> {
            for (String table : tables) {
                assertThat(jdbc.queryForList("select * from " + table)).as(table).isEmpty();
            }
            assertThat(jdbc.queryForList("select * from tenant")).isEmpty();
            assertThat(jdbc.queryForList("select * from app_user")).isEmpty();
            return null;
        });
        // Outside any transaction, too: the setting is never left on a pooled connection.
        for (String table : tables) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        }
        assertThat(venues.findAll()).isEmpty();
        assertThat(products.findAll()).isEmpty();
    }

    @Test
    void cannotWriteRowsForAnotherTenant() {
        assertThatThrownBy(() -> asTenant(a.tenantId(), () -> jdbc.update(
                "insert into route (id, tenant_id, venue_id, name) values (gen_random_uuid(), ?, ?, 'Intrusa')",
                b.tenantId(), b.venueId())))
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void auditLogIsAppendOnly() {
        assertThatThrownBy(() -> asTenant(a.tenantId(), () -> jdbc.update("update audit_log set action = 'x'")))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asTenant(a.tenantId(), () -> jdbc.update("delete from audit_log")))
                .rootCause().hasMessageContaining("permission denied");
    }

    private List<String> tenantOwnedTables() {
        return jdbc.queryForList("""
                select table_name from information_schema.columns
                where table_schema = 'public' and column_name = 'tenant_id'
                order by table_name""", String.class);
    }

    private <T> T asTenant(UUID tenantId, Supplier<T> action) {
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> action.get()));
    }
}
