-- Row-Level Security and runtime grants.
--
-- The runtime role standpos_app owns nothing and has no BYPASSRLS. Each transaction sets
-- app.tenant_id with set_config(..., true). When it is unset or empty, nullif() turns it into NULL,
-- the comparison is never true and every tenant-owned table returns zero rows (fail closed).
-- FORCE makes the policies apply to the table owner as well.

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO standpos_app;

-- Tenant-owned tables: one policy each, on tenant_id.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['usage_counter', 'venue', 'area', 'membership', 'device', 'device_enrolment_code',
                             'route', 'category', 'product', 'audit_log']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
            'WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    END LOOP;
END
$$;

-- The tenant row itself is visible only to its own tenant.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Users are global. A tenant sees the users that hold a membership in it (membership is itself
-- filtered by RLS). Any tenant may insert a user: it becomes visible once a membership links it.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;
CREATE POLICY member_of_tenant_select ON app_user FOR SELECT
    USING (EXISTS (SELECT 1 FROM membership m WHERE m.user_id = app_user.id));
CREATE POLICY member_of_tenant_update ON app_user FOR UPDATE
    USING (EXISTS (SELECT 1 FROM membership m WHERE m.user_id = app_user.id))
    WITH CHECK (true);
CREATE POLICY any_tenant_insert ON app_user FOR INSERT
    WITH CHECK (true);

-- Runtime grants. Nothing is granted on flyway_schema_history.
GRANT SELECT ON plan TO standpos_app;
GRANT SELECT, INSERT, UPDATE ON tenant, app_user TO standpos_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON usage_counter, venue, area, membership, device, device_enrolment_code,
    route, category, product TO standpos_app;
GRANT SELECT, INSERT ON audit_log TO standpos_app;
