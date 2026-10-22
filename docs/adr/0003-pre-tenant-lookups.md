# 0003 — Lookups before the tenant is known

- Status: accepted
- Date: 2026-10-22
- Phase: 0

## Context

Some reads happen before any tenant is known: login by email, the memberships of the user who just logged in, resolving a device token on every request, and claiming a one-time enrolment code. With RLS forced on every tenant-owned table and the runtime role `standpos_app` lacking `BYPASSRLS`, these reads return nothing. The prompt offered a narrow `SECURITY DEFINER` function or a dedicated non-RLS table.

## Decision

- **`app_user` is global** (a person may belong to several tenants) but still has RLS: a tenant sees the users holding a membership in it. Any tenant may insert a user, who becomes visible once a membership links them.
- The pre-tenant reads are **`SECURITY DEFINER` functions in schema `auth`**, owned by **`standpos_auth`**, a `NOLOGIN` role without `BYPASSRLS`:
  - `auth.find_login(email)`, `auth.record_login_failure(user, max, lock)`, `auth.record_login_success(user)`
  - `auth.user_memberships(user)` (active tenants only)
  - `auth.find_device(token_hash)` (non-revoked devices of active tenants)
  - `auth.claim_enrolment_code(code)` (atomic single use: `UPDATE … WHERE used_at IS NULL AND expires_at > now() RETURNING`)
- Cross-tenant visibility comes from **policies granted `TO standpos_auth`** on exactly the tables and commands these functions need. They apply only while a function runs as its owner.
- `standpos_owner` is a member of `standpos_auth` **`WITH INHERIT FALSE, SET TRUE`**: it can create and replace the functions (the migration runs `SET ROLE standpos_auth`) but does not inherit the cross-tenant policies.
- Functions set `search_path = pg_catalog, public, pg_temp` and `EXECUTE` is granted only to `standpos_app`.

## Consequences

- No table without RLS holds tenant data, and the application role cannot read across tenants with any SQL it writes; only the six narrow functions can.
- `RlsCoverageIT` asserts that every policy with `USING (true)` is granted to `standpos_auth` only and that this role cannot log in.
- The role script (`deploy/postgres/initdb/10-standpos-roles.sh`) creates three roles; it runs once, as the PostgreSQL superuser, on an empty data directory. Grants live in the migrations.
- PostgreSQL 16 or later is required for `WITH INHERIT FALSE, SET TRUE`; the stack uses 17.

## Alternatives considered

- **Dedicated non-RLS lookup table** (email → user, token hash → device): duplicates data that must stay in sync, and a table without RLS that holds tenant ids is exactly what the coverage test is meant to catch.
- **`BYPASSRLS` owner for the functions:** works, but a role that bypasses every policy on every table is broader than needed.
