# StandPOS — instructions for Claude Code

StandPOS is a hosted, multi-tenant point of sale for non-profit associations, parish youth centres (oratori) and food stands. It issues **non-fiscal courtesy receipts only**, keeps per-product stock, and takes card payments on a **SumUp Solo** driven server-side through the SumUp Cloud API.

- **Source of truth:** [`docs/SPEC.md`](docs/SPEC.md). Read it before any task. If the code and the spec disagree, the spec wins unless an ADR in `docs/adr/` says otherwise.
- **Work plan:** one phase at a time, following `docs/prompts/phase-N.md`.
- **Repository:** https://github.com/projectsrl-git/standpos

## Repository layout (target)

```
standpos/
├── pom.xml                  Maven parent (Java modules)
├── backend/                 Spring Boot application (REST, WebSocket, SumUp client)
├── print-protocol/          Shared DTOs: the structured print document sent to agents
├── print-agent/             Windows service that prints ESC/POS on the register's USB printer
├── frontend/                React + TypeScript (Vite) PWA: register, manager area
├── deploy/                  docker-compose.yml, Caddyfile, .env.example, scripts
└── docs/                    SPEC.md, prompts/, adr/, guides (development, deploy, user guides)
```

## Stack — fixed, do not substitute

| Area | Choice |
| --- | --- |
| Language / runtime | Java 25 (LTS) |
| Backend | Spring Boot 4.x (latest GA), Spring Security, Spring Data JPA (Hibernate), Flyway, Spring Session JDBC, WebSocket + STOMP |
| Database | PostgreSQL 17 with Row-Level Security |
| Frontend | React + TypeScript + Vite, installable PWA, i18n (Italian first) |
| Print agent | Java 25, escpos-coffee, SQLite (xerial), packaged with jpackage, run as a Windows service |
| Build | Maven multi-module (Java), npm (frontend) |
| Tests | JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock (SumUp), Vitest + Testing Library |
| Deploy | Docker Compose, Caddy (automatic TLS), multi-arch images `linux/amd64` + `linux/arm64` |

Adding a dependency outside this list needs a one-line justification in the PR description.

## Non-negotiable rules

1. **Non-fiscal only.** Every receipt carries the fixed line `Scontrino di cortesia – documento non fiscale`, generated server-side and not configurable by tenants. Never implement fiscal documents, telematic registers or Agenzia delle Entrate integrations.
2. **Money is integer cents** (`long` / `bigint`), currency EUR. No `float`/`double` for amounts anywhere.
3. **Tenant isolation, twice.** Every tenant-owned table has `tenant_id uuid NOT NULL`, RLS `ENABLE` + `FORCE`, and a policy on `current_setting('app.tenant_id', true)`. The application connects with a role that is neither table owner nor `BYPASSRLS`, and sets the tenant per transaction with `set_config('app.tenant_id', ?, true)`. A Hibernate tenant filter is the first line; RLS is the backstop. No tenant set ⇒ zero rows (fail closed).
4. **Stock is a ledger.** `stock_movement` is append-only (no `UPDATE`/`DELETE`, enforced in the database). `stock_level` is updated in the same transaction as the movement.
5. **Snapshots.** Order lines copy product name and price at sale time.
6. **Idempotency.** Orders are created with a client-generated UUID; a retry returns the existing order. SumUp `client_transaction_id` is unique.
7. **SumUp is server-side only.** Each tenant supplies its own API key, merchant code, affiliate app id and affiliate key (self-service, no OAuth, nothing that needs SumUp staff). The webhook is a hint: confirm every result through the Transactions API before marking an order paid. Never parse `failure_reason`; show it as text.
8. **Secrets** (SumUp keys) are encrypted with AES-256-GCM using a master key from the environment. Never log them, never return them to the browser.
9. **Usage cap.** 2,000 sales per tenant per calendar month, counted inside the sale transaction whether or not a receipt is printed.
10. **Out of scope** (do not build unless a later prompt says so): offline selling, OAuth with SumUp, online pre-orders, waiter phones, recipes/bill of materials, add-ons, set menus, cover charge, takeaway fee, vouchers, staff meals, accounting, invoicing, billing.

## Conventions

- **Language:** code, identifiers, comments, commits and docs in English. UI text in Italian through i18n keys; no hard-coded user-facing strings in components.
- **Talking to the user:** always reply and ask questions in Italian (chat messages, summaries, questions and choices), whatever the language of the code and docs.
- **Java packages:** `it.projectsrl.standpos.<feature>`, package by feature (`tenant`, `venue`, `auth`, `device`, `catalog`, `stock`, `sales`, `payment`, `sumup`, `printing`, `usage`, `audit`, `report`).
- **IDs:** UUID (v7) generated in the application.
- **Time:** `timestamptz` in UTC in the database; each venue has a time zone (default `Europe/Rome`) and a day-change time.
- **REST:** under `/api/v1`, JSON, errors as RFC 9457 problem details. WebSocket/STOMP under `/ws`.
- **Migrations:** Flyway `V<n>__<snake_description>.sql`. Never edit a migration that has been merged.
- **Security:** cookie sessions with CSRF protection for browsers; device tokens are random 256-bit values stored as SHA-256 hashes.
- **Logging:** structured JSON; no personal data or secrets in logs.

## Workflow

1. Work on a branch `phase-N` (or `phase-N/<topic>`); Conventional Commits; small, focused commits.
2. Before declaring a task done: `mvn -B verify` green (including Testcontainers), frontend `npm run lint && npm test && npm run build` green, `docker compose -f deploy/docker-compose.yml up` starts cleanly.
3. **Every phase updates the docs:** `README.md`, `CHANGELOG.md` (Keep a Changelog format) and the relevant guides in `docs/`. A phase without updated docs is not finished.
4. When the spec is silent or ambiguous, choose the simplest option consistent with it, record it as a short ADR in `docs/adr/NNNN-title.md`, and list it in the PR description. When a choice is expensive to reverse, stop and ask.
5. End each phase with a PR to `main` that lists: what was built, deviations from the spec, open questions.

## Commands

| Task | Command |
| --- | --- |
| Build and test all Java modules (unit + Testcontainers integration) | `./mvnw -B verify` |
| Backend tests without Docker | `STANDPOS_TEST_JDBC_URL=jdbc:postgresql://… ./mvnw -B verify` (see `docs/development.md`) |
| Dev database (PostgreSQL 17 on localhost:5432) | `docker compose -f deploy/docker-compose.dev.yml up -d` |
| Run the backend in dev (migrates, bootstraps a demo tenant) | `./mvnw -pl backend -am spring-boot:run -Dspring-boot.run.profiles=dev` |
| Run the frontend in dev (http://localhost:5173, proxies `/api`) | `cd frontend && npm ci && npm run dev` |
| Frontend checks | `cd frontend && npm run lint && npm test && npm run build` |
| Format the frontend | `cd frontend && npm run format` |
| Run the full stack (https://localhost) | `cp deploy/.env.example deploy/.env` then `docker compose -f deploy/docker-compose.yml up -d --build --wait` |
| Seed the first tenant, venue and owner | `docker compose -f deploy/docker-compose.yml run --rm backend --bootstrap` |
| One-off database dump | `docker compose -f deploy/docker-compose.yml run --rm backup --now` |

Dev owner (dev profile only): `owner@standpos.test` / `dev-owner-login`, PIN `1234`.
