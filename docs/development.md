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
print-protocol/    Shared print document model (placeholder in Phase 0)
print-agent/       Windows print agent (placeholder in Phase 0)
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

4. Walk the Phase 0 flow: log in as the owner, create a category and a product, open **Dispositivi** and generate a code for a *Cassa*, go to `/enrol` and enter it, then on `/pin` pick the owner and type `1234`. The register page shows the product.

In the `dev` profile cookies are not marked `Secure` (Vite serves plain HTTP) and logs are plain text; every other profile logs structured JSON (ECS) with a `requestId` on each line.

## Tests

```bash
./mvnw -B verify                                # all Java modules: unit + integration tests
cd frontend && npm run lint && npm test && npm run build
```

- Unit tests are `*Test` (Surefire); integration tests are `*IT` (Failsafe) and start a `postgres:17-alpine` container through Testcontainers, initialised with `deploy/postgres/initdb/10-standpos-roles.sh`.
- The key suites: `RlsIsolationIT` and `RlsCoverageIT` (tenant isolation, acceptance criteria 2 and 3), `AuthIT` (login, lockout, CSRF, PIN, devices), `ControllerAuthorizationCoverageIT` (every endpoint has a rule), `CatalogIT`.

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
