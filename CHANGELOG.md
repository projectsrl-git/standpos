# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Print agent: a printer named `text:<path>` writes a readable plain-text preview of each job to that file (same layout as the paper, Unicode kept, `[cut]` and `[drawer]` markers), for demos without a receipt printer.

### Changed

- `CLAUDE.md`: replies and questions to the user in Italian.

## [0.2.0] - 2026-10-26

Phase 1 — Register and stock.

### Added

- Stock ledger: append-only `stock_movement` (receipt, sale, sale reversal, return to stock, waste, adjustment, count) with `stock_level` updated in the same transaction, quantity and value in integer cents, weighted average cost without floating point (ADR 0007); negative stock as a venue option.
- Suppliers (tenant-wide), goods receipts (draft, then posted and immutable), stock counts posting the differences, adjustments and waste with a mandatory reason, low-stock list, manual sold-out toggle; all audited.
- Cash sales in one transaction: idempotent on the register's client UUID, stock rows locked `FOR UPDATE` in product order, order lines snapshotting name, price and route, daily order number per venue reset at the venue's day change, cash with change and manual card flagged for reconciliation (ADR 0008).
- "Senza scontrino" (sale without printing, drawer still opens for cash), receipt printed later or reprinted from the order list, voids approved by a manager PIN on the register with each line back to stock or to waste, cash drawer shifts with opening float and counted cash.
- Monthly usage cap counted in the sale transaction: owner banner from 80%, sales continue until the venue's next day change after 100%, then refused until the next month; platform admin endpoint to raise a tenant's cap.
- WebSocket/STOMP at `/ws`: live stock and sold-out, printer online/offline and print job results on every register of the venue, with per-frame authorisation and the tenant context kept on message handling (ADR 0009).
- Printing: versioned print protocol, printers and the register-to-printer link, server-side job queue with redelivery and expiry, courtesy receipt layout with the fixed non-fiscal line, test page (ADR 0012).
- Print agent v1: Windows service (jpackage + WinSW, ADR 0011) connecting out over WSS with its bearer token, SQLite queue surviving reboots, ESC/POS through the Windows spooler with code page CP858 (€), retries with backoff, heartbeat every 15 s.
- Print agent enrolment on its own CSRF-free endpoint `POST /api/v1/agents/enrol` that sets no cookie (ADR 0010).
- Manual unlock of password and PIN lockouts (owners; venue managers for PINs), with the lock state in the manager area; audited.
- Register UI: cart with quantities and notes, payment dialog, order list, void dialog, shift open and close, banners for printer offline, print failure, usage cap and lost connection. Manager UI: stock, goods receipts, counts, suppliers, printers, locks.
- Documentation: cashier guide and manager guide (Italian), print agent setup guide, manual POS80 checklist, ADRs 0007–0012.

### Changed

- A product referenced by orders or stock can no longer be deleted (`409 catalog.product_in_use`); it is deactivated instead.
- `POST /api/v1/devices/enrol` enrols browsers only (register, kitchen screen) and rejects print agent codes without burning them.
- Product responses carry `inUse`; venue responses carry `allowNegativeStock`; user responses carry the lock times.

## [0.1.0] - 2026-10-22

Phase 0 — Foundations.

### Added

- Maven multi-module build (Spring Boot 4.1, Java 25) with the Maven wrapper: `backend`, plus `print-protocol` and `print-agent` placeholders.
- PostgreSQL 17 schema (Flyway): plans with the free plan (2,000 sales per month), tenants, usage counters, venues, areas, users, memberships, devices, enrolment codes, routes, categories, products, append-only audit log, Spring Session tables.
- Tenant isolation twice: Hibernate `@TenantId` and Row-Level Security enabled and forced on every tenant-owned table, applied per transaction with `set_config('app.tenant_id', …, true)`; runtime role without ownership or `BYPASSRLS`; pre-tenant lookups through narrow `SECURITY DEFINER` functions.
- Owner and manager login with Argon2id, per-IP rate limiting and account lockout; sessions in PostgreSQL (Spring Session JDBC); SPA-compatible CSRF protection.
- Device enrolment with one-time codes, hashed device tokens, immediate revocation; cashier PIN login only from a register of the same venue, with PIN lockout.
- Manager API and pages for venues, users and memberships, devices, routes, categories and products; price and role changes audited.
- React + TypeScript installable PWA (Italian): login, manager area, device enrolment, PIN keypad, read-only register grid.
- RFC 9457 problem details with stable error codes; structured JSON logs with a request id; Actuator health.
- Bootstrap command (`--bootstrap`) creating the first tenant, venue and owner from the environment.
- Docker images for `linux/amd64` and `linux/arm64`, Docker Compose stack with Caddy (automatic TLS) and nightly backups, GitHub Actions CI.
- Documentation: README, development guide, Oracle Cloud demo deployment guide, ADRs 0001–0006.
- Technical specification (`docs/SPEC.md`), development instructions (`CLAUDE.md`) and phase prompts.
