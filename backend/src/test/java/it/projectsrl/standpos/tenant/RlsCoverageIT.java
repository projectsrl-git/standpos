package it.projectsrl.standpos.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance criterion 3: the PostgreSQL catalogue proves that every table with a {@code tenant_id} column has RLS
 * enabled and forced and a policy on {@code app.tenant_id}. Every other table must be on the allow-list below, so a
 * new table cannot slip in without a decision.
 */
class RlsCoverageIT extends IntegrationTest {

    /** Tables without tenant_id, each with the reason it is not tenant-owned. */
    private static final Map<String, String> NON_TENANT_TABLES = Map.of(
            "plan", "global catalogue of plans, read-only for the application",
            "tenant", "the tenant row itself: RLS on id, checked below",
            "app_user", "global users: RLS through membership, checked below",
            "spring_session", "HTTP sessions; the tenant comes from the stored principal",
            "spring_session_attributes", "HTTP session attributes",
            "flyway_schema_history", "migration history; no grants to the application role");

    private static final Set<String> RLS_WITHOUT_TENANT_ID = Set.of("tenant", "app_user");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyTenantOwnedTableHasForcedRlsAndATenantPolicy() {
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select c.relname as name,
                       c.relrowsecurity as rls,
                       c.relforcerowsecurity as forced,
                       exists (select 1 from information_schema.columns col
                               where col.table_schema = 'public' and col.table_name = c.relname
                                 and col.column_name = 'tenant_id') as has_tenant_id,
                       (select count(*) from pg_policies p
                        where p.schemaname = 'public' and p.tablename = c.relname
                          and p.qual like '%app.tenant_id%' and p.with_check like '%app.tenant_id%') as tenant_policies
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind in ('r', 'p')
                order by c.relname""");

        assertThat(tables).isNotEmpty();
        for (Map<String, Object> table : tables) {
            String name = (String) table.get("name");
            if ((Boolean) table.get("has_tenant_id")) {
                assertThat(table.get("rls")).as("RLS enabled on %s", name).isEqualTo(true);
                assertThat(table.get("forced")).as("RLS forced on %s", name).isEqualTo(true);
                assertThat((Long) table.get("tenant_policies")).as("tenant policy on %s", name).isPositive();
            } else {
                assertThat(NON_TENANT_TABLES).as("%s has no tenant_id and is not allow-listed", name)
                        .containsKey(name);
            }
            if (RLS_WITHOUT_TENANT_ID.contains(name)) {
                assertThat(table.get("rls")).as("RLS enabled on %s", name).isEqualTo(true);
                assertThat(table.get("forced")).as("RLS forced on %s", name).isEqualTo(true);
            }
        }
    }

    @Test
    void tenantTableIsFilteredOnItsOwnId() {
        String qual = jdbc.queryForObject(
                "select qual from pg_policies where tablename = 'tenant' and policyname = 'tenant_isolation'",
                String.class);
        assertThat(qual).contains("app.tenant_id").contains("id =");
    }

    @Test
    void crossTenantPoliciesAreGrantedOnlyToTheNologinAuthRole() {
        List<Map<String, Object>> policies = jdbc.queryForList(
                "select tablename, policyname, roles::text as roles from pg_policies where qual = 'true'");
        assertThat(policies).allSatisfy(p -> assertThat(p.get("roles")).as(p.get("policyname").toString())
                .isEqualTo("{standpos_auth}"));
        Map<String, Object> auth = jdbc.queryForMap(
                "select rolcanlogin, rolbypassrls from pg_roles where rolname = 'standpos_auth'");
        assertThat(auth).containsEntry("rolcanlogin", false).containsEntry("rolbypassrls", false);
    }

    @Test
    void applicationRoleCannotReadMigrationHistory() {
        Boolean canRead = jdbc.queryForObject(
                "select has_table_privilege('flyway_schema_history', 'select')", Boolean.class);
        assertThat(canRead).isFalse();
    }
}
