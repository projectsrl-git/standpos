# Development guide

How to build, run and test StandPOS on a developer machine.

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 25 | Temurin recommended. Maven comes with the wrapper (`./mvnw`, `mvnw.cmd`). |
| Node.js | 24 (see `.nvmrc`) | npm is bundled. |
| Docker | Engine 24+ or Docker Desktop | For Testcontainers, the dev database and the Compose stack. |
| Git | any | The repository uses LF line endings (`.gitattributes`); on Windows keep `core.autocrlf=false` or `input`. |

## Repository layout

```
pom.xml            Maven parent (Spring Boot 4.1, Java 25)
backend/           Spring Boot application, package it.projectsrl.standpos.<feature>
print-protocol/    Structured print document, job, ack and heartbeat shared by backend and agent
print-agent/       Windows print agent: STOMP client, SQLite queue, ESC/POS (escpos-coffee), jpackage + WinSW
frontend/          React + TypeScript PWA (Vite)
deploy/            docker-compose.yml, docker-compose.dev.yml, Caddyfile, .env.example, scripts
docs/              SPEC.md, ADRs, guides, phase prompts
```

## Run in dev mode

1. Start PostgreSQL 17 with the StandPOS roles (published on `127.0.0.1:5432`):

   ```bash
   docker compose -f deploy/docker-compose.dev.yml up -d
   ```

2. Start the backend with the `dev` profile. Flyway migrates the schema and the dev bootstrap creates a tenant "Oratorio di prova", venue "Bar oratorio" and an owner `owner@standpos.test` / `dev-owner-login` with PIN `1234` (values in `application-dev.yml`, local only):

   ```bash
   ./mvnw -pl backend -am spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   The backend listens on `http://localhost:8080`. Set `PORT` to use another port.

3. Start the frontend. Vite serves `http://localhost:5173` and proxies `/api` to the backend (`STANDPOS_BACKEND_URL` overrides the target, for example `http://localhost:8081`):

   ```bash
   cd frontend
   npm ci
   npm run dev
   ```

4. Walk the flow: log in as the owner, create a category and a product with **Gestisci magazzino**, post a goods receipt in **Carichi**, open **Dispositivi** and generate a code for a *Cassa*, go to `/enrol` and enter it, then on `/pin` pick the owner and type `1234`. Open the shift, sell for cash and watch the stock change in **Magazzino**.

5. Optionally run the print agent against the dev backend, printing to a file instead of a printer. Generate an *Agente di stampa* code in **Dispositivi**, then:

   ```bash
   ./mvnw -B -pl print-agent -am package -DskipTests
   export STANDPOS_AGENT_HOME=/tmp/standpos-agent        # instead of %ProgramData%\StandPOS\PrintAgent
   java -jar print-agent/target/print-agent-0.2.0.jar enrol --server http://localhost:8080 --code ABCD2345
   java -jar print-agent/target/print-agent-0.2.0.jar run
   ```

   In **Stampanti** add a printer on that agent with the Windows name `file:/tmp/standpos-agent/paper.bin`, link it to the register and press **Stampa di prova**: the ESC/POS bytes land in that file. With `text:/tmp/standpos-agent/receipts.txt` instead, the agent writes a readable plain-text preview of each job, handy for demos ([print-agent-setup.md](print-agent-setup.md#demo-without-a-receipt-printer)). Plain `http://` is accepted only for `localhost` and `.test` hosts. The Windows packaging is in [print-agent-setup.md](print-agent-setup.md).

The Vite dev server proxies `/api` and the WebSocket endpoint `/ws`; the `dev` profile allows its origin (`standpos.websocket.allowed-origins`).

In the `dev` profile cookies are not marked `Secure` (Vite serves plain HTTP) and logs are plain text; every other profile logs structured JSON (ECS) with a `requestId` on each line.

## Tests

```bash
./mvnw -B verify                                # all Java modules: unit + integration tests
cd frontend && npm run lint && npm test && npm run build
```

- Unit tests are `*Test` (Surefire); integration tests are `*IT` (Failsafe) and start a `postgres:17-alpine` container through Testcontainers, initialised with `deploy/postgres/initdb/10-standpos-roles.sh`.
- The key suites: `RlsIsolationIT` and `RlsCoverageIT` (tenant isolation on every tenant-owned table, append-only `audit_log` and `stock_movement`), `AuthIT` (login, lockout, CSRF, PIN, devices), `ControllerAuthorizationCoverageIT` (every endpoint has a rule), `CatalogIT`, and from Phase 1:
  - `SalesIT`: cash and manual card sales, idempotent retries, two registers racing for the last unit, daily number and day change, usage cap at 80%/100%/day change, voids, products in use;
  - `StockIT`: goods receipts, counts, adjustments, roles, and the ledger property test (movements always add up to the levels);
  - `UnlockIT`, `AgentEnrolmentIT`;
  - `RealtimeIT`: a running server (random port) with STOMP clients: job delivery to the agent, acknowledgements, live stock events, refused subscriptions, revocation.
- Tests that need a fixed time set the application clock: `IntegrationTest` exposes a `MutableClock` (`clock.set(Instant)`), reset after each test.
- The print agent's own tests (ESC/POS bytes, STOMP codec, SQLite queue, a fake server end to end) run in `print-agent` with no printer and no network.

### Without Docker

Point the integration tests at an existing, empty PostgreSQL 17 database initialised with the role script (see [ADR 0006](adr/0006-integration-test-database.md)):

```bash
createdb -h localhost -U postgres standpos_test
POSTGRES_USER=postgres POSTGRES_DB=standpos_test PGHOST=localhost \
  STANDPOS_OWNER_PASSWORD=test-owner-password STANDPOS_APP_PASSWORD=test-app-password \
  sh deploy/postgres/initdb/10-standpos-roles.sh
STANDPOS_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/standpos_test ./mvnw -B verify
```

Drop and recreate the database (and the three `standpos_*` roles) before each run.

## Seeding and bootstrap

There is no self-service sign-up yet (Phase 4). The first tenant, venue and owner come from `STANDPOS_BOOTSTRAP_*` environment variables:

- `dev` profile: automatic at startup (`standpos.bootstrap.on-startup=true`).
- Any other profile: `java -jar standpos-backend.jar --bootstrap` (or `docker compose -f deploy/docker-compose.yml run --rm backend --bootstrap`) creates them and exits. It does nothing if the owner email already exists.

Further users, venues, devices and the catalogue are created in the manager area.

## Conventions

See [`CLAUDE.md`](../CLAUDE.md): money in integer cents, UUIDv7 ids generated in the application, `timestamptz` in UTC, REST under `/api/v1` with RFC 9457 problem details (each with a stable `code` the frontend translates), Flyway `V<n>__<description>.sql` never edited once merged, Italian UI strings only through `frontend/src/i18n/it.json`.

### Adding a tenant-owned table

1. Give it `tenant_id uuid NOT NULL` and reference parents through `(tenant_id, id)`.
2. In the same migration: `ENABLE` and `FORCE ROW LEVEL SECURITY`, a `tenant_isolation` policy on `nullif(current_setting('app.tenant_id', true), '')::uuid`, and grants to `standpos_app`.
3. Map the entity on `TenantOwnedEntity` (Hibernate `@TenantId`).
4. Seed a row for both tenants in `RlsIsolationIT`; `RlsCoverageIT` fails until the table is covered.
