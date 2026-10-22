# Phase 0 — Foundations

> Paste this whole file into Claude Code, opened at the repository root. Read `CLAUDE.md` and `docs/SPEC.md` first; this prompt narrows the spec to what Phase 0 must deliver.

## Goal

A runnable, deployable skeleton on which every later phase builds: Maven and npm projects, PostgreSQL schema with **Row-Level Security proven by tests**, login for owners and managers, device enrolment with cashier PIN login, a basic catalogue, a frontend shell, Docker Compose for local and Oracle demo, and CI.

No sales, no stock movements, no printing, no SumUp yet.

## Scope

### 1. Repository scaffolding

- Maven parent `pom.xml` with modules `backend`, `print-protocol`, `print-agent`. `print-protocol` and `print-agent` only need to compile in this phase (empty `Main` and a placeholder DTO are enough).
- `frontend/`: Vite + React + TypeScript, ESLint, Prettier, Vitest + Testing Library.
- `deploy/`: `docker-compose.yml`, `Caddyfile`, `.env.example`.
- `.gitignore`, `.editorconfig`, `.gitattributes` (LF line endings in the repo; the author works on Windows).
- Maven wrapper (`mvnw`) and an `.nvmrc` for the Node version.

### 2. Backend application

- Spring Boot 4.x on Java 25, package root `it.projectsrl.standpos`.
- Profiles: `dev`, `test`, `demo`, `prod`. Configuration from environment variables; nothing secret in `application*.yml`.
- Health and info via Actuator (`/actuator/health` public, the rest protected).
- Errors as RFC 9457 problem details; validation errors list the offending fields.
- Structured JSON logging; a request id on every log line.

### 3. Database and tenancy

- Flyway migrations creating: `plan` (seed the free plan, 2,000 sales/month), `tenant`, `usage_counter`, `venue`, `area`, `app_user`, `membership`, `device`, `device_enrolment_code`, `route`, `category`, `product`, `audit_log`, plus the Spring Session JDBC tables.
- Two database roles: `standpos_owner` runs migrations; `standpos_app` is used at runtime, is not the table owner and has no `BYPASSRLS`. Grants are part of the migrations.
- Every tenant-owned table: `tenant_id uuid NOT NULL`, RLS `ENABLE` and `FORCE`, one policy comparing `tenant_id` with `current_setting('app.tenant_id', true)::uuid`.
- Tenant context: resolved from the authenticated principal (user membership or device token) and applied **per transaction** with `select set_config('app.tenant_id', :id, true)`. Choose the mechanism (custom transaction manager, connection customizer, aspect) and record it in an ADR. A Hibernate filter adds the same condition at application level.
- `app_user` is global (a person may belong to several tenants); memberships are tenant-scoped. Login lookup by email must work before a tenant is known: expose it through a narrow `SECURITY DEFINER` function or a dedicated non-RLS table, documented in the ADR.
- Bootstrap: a command (Spring profile or CLI flag) that creates a tenant, a venue and an owner user from environment variables, used for dev and the Oracle demo. Self-service sign-up is Phase 4.

### 4. Authentication and authorisation

- Email + password login for owners and managers; Argon2id (Spring Security `Argon2PasswordEncoder`, which needs Bouncy Castle).
- Sessions in Spring Session JDBC; HttpOnly, Secure, SameSite=Lax cookies; CSRF protection compatible with the SPA (cookie-to-header token).
- Login rate limiting and temporary lockout after repeated failures.
- Roles per venue: `PLATFORM_ADMIN`, `OWNER`, `MANAGER`, `STOCK_CLERK`, `CASHIER`, `KITCHEN`. Method security on every endpoint; a test asserts that no controller method is left without an authorisation rule.
- TOTP is **not** in this phase (Phase 4).

### 5. Devices and cashier PIN

- A manager creates an enrolment code for a venue and a device kind (`REGISTER`, `KITCHEN`, `PRINT_AGENT`): 8 characters without ambiguous symbols, valid 10 minutes, single use.
- The device exchanges the code for a device token (random 256 bits; only its SHA-256 hash is stored). Browsers keep it in an HttpOnly cookie; the print agent will send it as a bearer token.
- Managers list, rename and revoke devices; a revoked token stops working immediately.
- Cashier PIN (4–6 digits, Argon2-hashed) is accepted **only** from an enrolled register device of the same venue; 5 failed attempts lock the PIN for 15 minutes.

### 6. Catalogue (no stock yet)

- CRUD for `route`, `category` (name, colour, sort, default route) and `product` (name, price in cents, route override, active, `track_stock`, `low_threshold`, `sold_out`).
- Price changes and role changes write to `audit_log`.

### 7. Frontend

- PWA shell (manifest, service worker for the app shell only — no offline data).
- i18n with Italian as the only language for now; all strings via keys.
- Pages: login; manager area with venues, users and memberships, devices (create enrolment code, list, revoke), routes, categories, products; device enrolment page (enter code); cashier PIN page; register page showing the product grid by category, read-only (no cart yet).
- Touch-first: minimum 48 px targets, works at 1280×800 and on a 10" tablet.

### 8. Deployment

- Multi-stage Dockerfiles for backend and frontend; images build for `linux/amd64` and `linux/arm64`.
- `deploy/docker-compose.yml`: `postgres` (17), `backend`, `caddy` (serves the frontend build and proxies `/api`, `/ws`, `/webhooks`, `/actuator/health`). Named volumes; healthchecks.
- Works locally with `https://localhost` (Caddy internal CA) and on a host with a real domain via `STANDPOS_DOMAIN`.
- Nightly backup script (`pg_dump`, keep 30) as a separate service or documented cron.

### 9. CI

- GitHub Actions: `mvn -B verify` (Testcontainers), frontend lint + test + build, Docker buildx for both architectures (build only, no push).

### 10. Documentation

- `README.md`: what StandPOS is, status, quick start (local Compose and dev mode), repository layout.
- `docs/development.md`: prerequisites, running backend and frontend in dev, running tests, seeding.
- `docs/deploy-oracle.md`: step-by-step demo deployment on an Oracle Always Free Ampere A1 VM (EU home region, security list and VM firewall for 80/443, DNS, Docker install on Oracle Linux or Ubuntu arm64, `.env`, bootstrap, backup to Object Storage, idle-reclamation note from the spec).
- `docs/adr/`: one ADR per decision taken in this phase (at least tenant context mechanism and global user lookup).
- `CHANGELOG.md`: a `0.1.0` entry.
- Fill the **Commands** section of `CLAUDE.md`.

## Out of scope

Sales, cart, payments, stock ledger and goods receipts, usage counting logic (the table exists), printing and the real print agent, SumUp, reports, kitchen screens, TOTP, self-service sign-up, email sending.

## Acceptance criteria

1. `mvn -B verify` is green, including Testcontainers integration tests.
2. **RLS isolation test:** with two tenants holding data, connecting as `standpos_app` with tenant A set, every tenant-owned table — queried both through repositories and with native SQL `SELECT *` — returns only A's rows; with no tenant set it returns zero rows.
3. **RLS coverage test:** a query on the PostgreSQL catalogue proves every table with a `tenant_id` column has RLS enabled and forced and at least one policy; an explicit allow-list covers the non-tenant tables (e.g. sessions, plan).
4. Auth tests: login success and failure, lockout, CSRF required on state-changing requests, PIN rejected from a non-enrolled or other-venue device, PIN lockout, revoked device token rejected.
5. Authorisation test: every controller method has an explicit rule.
6. From a clean clone, `docker compose -f deploy/docker-compose.yml up` brings the stack up; after bootstrap, the owner logs in at `https://localhost`, creates a category and a product, enrols the browser as a register, logs in with a cashier PIN and sees the product on the register page.
7. Both architectures build in CI.
8. Docs listed above exist and match what was built.

## Deliverable

Branch `phase-0`, Conventional Commits, PR to `main` whose description lists: what was built, ADRs written, any deviation from the spec, open questions for Phase 1.
