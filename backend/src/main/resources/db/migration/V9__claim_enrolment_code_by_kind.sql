-- Browsers and print agents enrol on different endpoints (docs/adr/0010-print-agent-enrolment.md). The claim now takes
-- the kinds the endpoint accepts: a valid code of another kind is reported (claimed = false) but not burnt, so the
-- person can still use it on the right endpoint.

SET ROLE standpos_auth;

DROP FUNCTION auth.claim_enrolment_code(text);

CREATE FUNCTION auth.claim_enrolment_code(p_code text, p_kinds text[])
    RETURNS TABLE (code_id uuid, tenant_id uuid, venue_id uuid, kind text, claimed boolean)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    RETURN QUERY
        WITH done AS (
            UPDATE device_enrolment_code c
            SET used_at = now(), updated_at = now()
            WHERE c.code = upper(p_code) AND c.used_at IS NULL AND c.expires_at > now() AND c.kind = ANY (p_kinds)
            RETURNING c.id, c.tenant_id, c.venue_id, c.kind)
        SELECT d.id, d.tenant_id, d.venue_id, d.kind, true FROM done d;
    IF NOT FOUND THEN
        RETURN QUERY
            SELECT c.id, c.tenant_id, c.venue_id, c.kind, false
            FROM device_enrolment_code c
            WHERE c.code = upper(p_code) AND c.used_at IS NULL AND c.expires_at > now();
    END IF;
END
$$;

REVOKE ALL ON FUNCTION auth.claim_enrolment_code(text, text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.claim_enrolment_code(text, text[]) TO standpos_app;

RESET ROLE;
