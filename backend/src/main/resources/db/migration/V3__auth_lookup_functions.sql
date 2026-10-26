-- Narrow lookups needed before a tenant is known: login by email, the user's memberships,
-- device token resolution and single-use enrolment code claims. See docs/adr/0003-pre-tenant-lookups.md.
--
-- The functions are SECURITY DEFINER and owned by standpos_auth, a NOLOGIN role. RLS policies granted
-- TO standpos_auth let these functions, and nothing else, read across tenants. standpos_owner is a
-- member of standpos_auth WITH INHERIT FALSE, SET TRUE: it can create the functions but does not
-- inherit the policies.

CREATE SCHEMA auth AUTHORIZATION standpos_auth;
GRANT USAGE ON SCHEMA public TO standpos_auth;

GRANT SELECT ON tenant, app_user, membership, device, device_enrolment_code TO standpos_auth;
GRANT UPDATE (failed_login_attempts, locked_until, updated_at) ON app_user TO standpos_auth;
GRANT UPDATE (used_at, updated_at) ON device_enrolment_code TO standpos_auth;

CREATE POLICY auth_lookup ON tenant FOR SELECT TO standpos_auth USING (true);
CREATE POLICY auth_lookup ON app_user FOR SELECT TO standpos_auth USING (true);
CREATE POLICY auth_login_update ON app_user FOR UPDATE TO standpos_auth USING (true) WITH CHECK (true);
CREATE POLICY auth_lookup ON membership FOR SELECT TO standpos_auth USING (true);
CREATE POLICY auth_lookup ON device FOR SELECT TO standpos_auth USING (true);
CREATE POLICY auth_lookup ON device_enrolment_code FOR SELECT TO standpos_auth USING (true);
CREATE POLICY auth_claim ON device_enrolment_code FOR UPDATE TO standpos_auth USING (true) WITH CHECK (true);

-- Everything below is owned by standpos_auth, so it is created and granted while acting as that role.
SET ROLE standpos_auth;

REVOKE ALL ON SCHEMA auth FROM PUBLIC;
GRANT USAGE ON SCHEMA auth TO standpos_app;

-- Credentials for one email. Returns no row when the email is unknown.
CREATE FUNCTION auth.find_login(p_email text)
    RETURNS TABLE (user_id uuid, password_hash text, status text, platform_admin boolean, locked_until timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT u.id, u.password_hash, u.status, u.platform_admin, u.locked_until
    FROM app_user u
    WHERE u.email = lower(p_email)
$$;

-- Counts a failed password; after p_max_attempts consecutive failures, locks the account for p_lock.
CREATE FUNCTION auth.record_login_failure(p_user_id uuid, p_max_attempts integer, p_lock interval)
    RETURNS timestamptz
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
    UPDATE app_user
    SET failed_login_attempts = CASE WHEN failed_login_attempts + 1 >= p_max_attempts THEN 0 ELSE failed_login_attempts + 1 END,
        locked_until          = CASE WHEN failed_login_attempts + 1 >= p_max_attempts THEN now() + p_lock ELSE locked_until END,
        updated_at            = now()
    WHERE id = p_user_id
    RETURNING locked_until
$$;

CREATE FUNCTION auth.record_login_success(p_user_id uuid)
    RETURNS void
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
    UPDATE app_user SET failed_login_attempts = 0, locked_until = NULL, updated_at = now() WHERE id = p_user_id
$$;

-- Memberships of one user in active tenants, oldest tenant first.
CREATE FUNCTION auth.user_memberships(p_user_id uuid)
    RETURNS TABLE (tenant_id uuid, venue_id uuid, role text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT m.tenant_id, m.venue_id, m.role
    FROM membership m
    JOIN tenant t ON t.id = m.tenant_id
    WHERE m.user_id = p_user_id AND t.status = 'ACTIVE'
    ORDER BY t.created_at, m.tenant_id
$$;

-- Resolves a device token hash. Revoked devices and suspended tenants resolve to nothing.
CREATE FUNCTION auth.find_device(p_token_hash bytea)
    RETURNS TABLE (device_id uuid, tenant_id uuid, venue_id uuid, kind text, name text, last_seen_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
    SELECT d.id, d.tenant_id, d.venue_id, d.kind, d.name, d.last_seen_at
    FROM device d
    JOIN tenant t ON t.id = d.tenant_id
    WHERE d.token_hash = p_token_hash AND d.revoked_at IS NULL AND t.status = 'ACTIVE'
$$;

-- Atomically marks an unused, unexpired code as used and returns where it belongs.
CREATE FUNCTION auth.claim_enrolment_code(p_code text)
    RETURNS TABLE (code_id uuid, tenant_id uuid, venue_id uuid, kind text)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
    UPDATE device_enrolment_code c
    SET used_at = now(), updated_at = now()
    WHERE c.code = upper(p_code) AND c.used_at IS NULL AND c.expires_at > now()
    RETURNING c.id, c.tenant_id, c.venue_id, c.kind
$$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA auth FROM PUBLIC;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA auth TO standpos_app;

RESET ROLE;
