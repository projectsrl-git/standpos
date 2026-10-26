# 0002 — Tenant context applied per transaction

- Status: accepted
- Date: 2026-10-22
- Phase: 0

## Context

Every tenant-owned table is protected twice: an application-level filter and PostgreSQL Row-Level Security on `current_setting('app.tenant_id', true)`. The setting must be applied per transaction with `set_config('app.tenant_id', :id, true)` so that a pooled connection never carries one tenant's id into another request. The prompt left the mechanism open: custom transaction manager, connection customizer or aspect.

## Decision

1. **`TenantContext`** holds the tenant of the current thread. `TenantContextFilter`, inside the security filter chain after authentication, sets it from the principal (user session, cashier session or device token). Code that knows the tenant before authentication (device enrolment, PIN login, bootstrap) uses `TenantContext.callAs(tenantId, ...)`.
2. **`TenantAwareJpaTransactionManager`** (a `JpaTransactionManager` subclass) runs `select set_config('app.tenant_id', ?, true)` on the transaction's connection right after `doBegin`. JDBC code (`JdbcTemplate`) in the same transaction shares the connection, so it sees the same setting.
3. **Hibernate `@TenantId`** on `TenantOwnedEntity` is the application-level filter: Hibernate adds `tenant_id = ?` to every query and load and fills `tenant_id` on insert. `TenantIdentifierResolver` reads the same `TenantContext`.
4. The value written to PostgreSQL is the tenant Hibernate bound to the session, so the two layers can never disagree. With no tenant, Hibernate gets a sentinel UUID (`00000000-…`) and PostgreSQL gets the empty string.
5. Policies compare with `nullif(current_setting('app.tenant_id', true), '')::uuid`. Once a session has used the setting, PostgreSQL returns `''` rather than NULL after the transaction ends; without `nullif` the cast would fail instead of returning zero rows.

## Consequences

- Repository access must happen inside a transaction to see any data; repositories are annotated `@Transactional(readOnly = true)` and `open-in-view` is off. Outside a transaction a query runs with no tenant and returns zero rows (fail closed), as `RlsIsolationIT` checks.
- The tenant must not change inside a transaction. Nested work for another tenant uses a new transaction inside `callAs`.
- Using `@TenantId` instead of a hand-written `@Filter` also covers `find()` by id and inserts, which a filter does not.

## Alternatives considered

- **Connection customizer (Hikari / DataSource proxy):** sets the value at checkout, but a connection is checked out before the request's transaction is known and `is_local` needs an open transaction.
- **Aspect around `@Transactional` services:** easy to bypass (repositories called directly, `TransactionTemplate`), and ordering with the transaction interceptor is fragile.
- **Session-level `SET` (is_local = false):** leaks across pooled connections unless every return path resets it.
