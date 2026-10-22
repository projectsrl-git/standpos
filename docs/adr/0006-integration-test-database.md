# 0006 — Integration test database

- Status: accepted
- Date: 2026-10-22
- Phase: 0

## Context

The RLS tests are only meaningful against real PostgreSQL 17, connected as the real roles. CI has Docker; some development machines (including the author's Windows PC at the time of Phase 0) do not.

## Decision

- Integration tests (`*IT`, run by Failsafe in `mvn verify`) use **Testcontainers** `postgres:17-alpine` by default, initialised with the **same role script** as Docker Compose, then Flyway migrates as `standpos_owner` and the application connects as `standpos_app`.
- When **`STANDPOS_TEST_JDBC_URL`** is set, the tests use that database instead (passwords from `STANDPOS_TEST_OWNER_PASSWORD` / `STANDPOS_TEST_APP_PASSWORD`). It must be a fresh database initialised with the role script; `docs/development.md` shows how.
- All integration tests share one Spring context and one database. Each test creates its own tenants through the application's services, so tests never depend on each other's data and need no cleanup.

## Consequences

- CI always runs the Testcontainers path. The external path exists for machines without Docker and is not a substitute for CI.
