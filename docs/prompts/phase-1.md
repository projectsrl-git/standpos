# Phase 1 — Register and stock

> Paste this whole file into Claude Code, opened at the repository root. Read `CLAUDE.md`, `docs/SPEC.md` and the ADRs in `docs/adr/` first; this prompt narrows the spec to what Phase 1 must deliver and builds on what Phase 0 (0.1.0) shipped.

## Goal

The oratorio can sell for cash on the register, print a courtesy receipt on the USB POS80, and run its stock: goods receipts, counts, adjustments, thresholds and automatic sold-out.

No SumUp yet (Phase 2), no separate bar/kitchen tickets and no printed shift report (Phase 3).

## What Phase 0 already provides — reuse it, do not rebuild it

- **Tenancy.** Tenant-owned entities extend `TenantOwnedEntity` (Hibernate `@TenantId`); `TenantAwareJpaTransactionManager` sets `app.tenant_id` per transaction; `TenantContext.callAs(tenantId, …)` where the tenant is known before authentication. Policies use `nullif(current_setting('app.tenant_id', true), '')::uuid` (ADR 0002). Pre-tenant reads go only through the `auth.*` `SECURITY DEFINER` functions owned by `standpos_auth` (ADR 0003).
- **Every new tenant-owned table** follows `docs/development.md` → "Adding a tenant-owned table": `tenant_id`, composite FKs on `(tenant_id, id)`, RLS `ENABLE` + `FORCE`, `tenant_isolation` policy, grants in the same migration, a seeded row for both tenants in `RlsIsolationIT`. `RlsCoverageIT` fails otherwise.
- **Authorisation.** Every controller method carries `@PreAuthorize("@authz…")` or `@PermitAll` (checked by `ControllerAuthorizationCoverageIT`; `@PermitAll` only for the paths in `SecurityConfig.PUBLIC_API`). Venue-scoped endpoints live under `/api/v1/venues/{venueId}/…`. Roles: OWNER tenant-wide, other roles per venue, memberships read per request (ADR 0004).
- **Devices and cashiers.** `CurrentDevice` resolved from the device token on every request; cashier sessions are bound to their register (ADR 0005). `PinLoginService` already verifies PINs with lockout: reuse its verification for the manager PIN on voids.
- **Errors and UI text.** `ApiException` with a stable `code`; every new code gets an Italian message under `errors.*` in `frontend/src/i18n/it.json`; all UI strings through i18n keys (`keys.test.ts` checks literal keys).
- **Money.** Integer cents end to end; frontend helpers in `frontend/src/money.ts` (no floating point).
- **Audit.** `AuditService.record(action, entityType, entityId, before, after)` inside the caller's transaction.
- **Tests.** Integration tests are `*IT` on Testcontainers; `Fixtures` creates tenants, venues, users and enrolled devices; `Browser` drives MockMvc with real session, device and CSRF cookies. Without Docker, `STANDPOS_TEST_JDBC_URL` points the tests at a local PostgreSQL 17 (ADR 0006); CI always runs Testcontainers.

## Decisions taken after Phase 0

1. **Product deletion.** Once order lines or stock movements reference a product, it can no longer be deleted: it is deactivated (`active = false`). `DELETE` on a referenced product returns `409 catalog.product_in_use`; the UI offers "Disattiva" instead of "Elimina" for such products. Never-used products may still be deleted.
2. **Session length.** 12 hours stays the timeout for manager and cashier sessions.
3. **Manual unlock.** Managers can unlock before the 15 minutes expire (see Scope §10).
4. **Print agent enrolment** uses its own CSRF-exempt endpoint (see Scope §8). Record it as an ADR.

## Scope

### 1. Stock ledger

- `stock_movement` (append-only: no `UPDATE`/`DELETE` granted to `standpos_app` and a trigger rejecting them, as for `audit_log`) and `stock_level` (venue, product, on hand) updated in the same transaction as the movement.
- Movement types: `receipt`, `sale`, `sale_reversal`, `return_to_stock`, `waste`, `adjustment`, `count`. Quantities are integer units.
- Negative stock off by default; venue setting `allow_negative_stock`.
- Valuation without floating point: keep the stock value in integer cents next to the quantity (for example `stock_level.value_cents`) and derive the weighted average cost when needed. Record the exact rule (rounding, what a `sale` removes from the value) in an ADR.

### 2. Suppliers and goods receipts

- `supplier` (tenant-wide: name, contacts). `goods_receipt` (venue, supplier, document reference, received at, status `DRAFT` → `POSTED`) and `goods_receipt_line` (product, quantity, optional unit cost in cents).
- Posting writes `receipt` movements and updates the stock value; a posted receipt is immutable. Audited.

### 3. Counts and adjustments

- Count session per venue: lists tracked products, the counted quantity is entered, posting writes a `count` movement for each difference with user and time.
- `adjustment` and `waste` with a mandatory reason. All audited.

### 4. Thresholds and sold-out, live

- Register shows the remaining quantity below the product's `low_threshold`; managers see a low-stock list.
- Automatic sold-out at zero (and back when stock returns), manual toggle ("finite le lasagne").
- Changes pushed live to every register of the venue over **WebSocket/STOMP at `/ws`**: authenticate the handshake with the existing session (browsers) or bearer device token (agent), authorise subscriptions per venue, and keep the tenant context on message handling. Record the STOMP security design in an ADR.

### 5. Shifts

- A cashier opens a shift on a register with an opening float and closes it with the counted cash. One open shift per register. The printed shift report is Phase 3.

### 6. Sale

- Cart on the register (quantity, line note, delete line), kept client-side (ADR 0001).
- `POST /api/v1/orders` with a client-generated UUID: a retry with the same UUID returns the existing order, never a second one.
- In one transaction: usage-cap check and counter increment; lock the `stock_level` rows of the products involved with `SELECT … FOR UPDATE` ordered by product id; reject the sale if a tracked product would go below zero (unless the venue allows it); `sale` movements; order lines snapshotting name and price; sold-out flags flipped.
- Daily order number per venue, reset at the venue's day change in its time zone (a per-venue, per-business-day counter incremented inside the sale transaction).
- Order statuses from the spec: `awaiting_payment` (used from Phase 2), `paid`, `cancelled`, `voided`.

### 7. Payment and receipt

- Cash: amount tendered and change shown, drawer kick through the printer. Manual card: recorded, flagged for reconciliation.
- **"Senza scontrino"** completes sale, payment and stock movements without printing; the receipt can be printed later from the order list.
- Receipt content from the spec: venue header, large order number, time, register, lines with prices, total, payment method and the fixed line `Scontrino di cortesia – documento non fiscale`, generated server-side and not configurable.

### 8. Printing

- **Print protocol** in `print-protocol`: versioned structured document (text lines, alignment, bold, double size, separator, logo, cut, drawer kick). The server builds the layout; the agent only renders it.
- **Printer and jobs:** `printer` (venue, print agent device, Windows printer name, width 80 mm, code page); a register is linked to its printer; `print_job` (venue, printer, kind, document, status, attempts) queued on the server and delivered to the agent; jobs stay queued while the agent is offline; reprint from the order list; the register shows "stampante offline" when heartbeats stop.
- **Print agent v1:** Java 25 Windows service packaged with jpackage; outbound WSS with its bearer device token; SQLite job queue (xerial) so jobs survive a reboot; ESC/POS via the Windows spooler by printer name (escpos-coffee), code page with the € sign; ack printed/failed; retry with backoff; heartbeat every 15 s. Choose the Windows service wrapper (for example Apache Commons Daemon procrun or WinSW) in an ADR, with the one-line dependency justification in the PR.
- **Agent enrolment:** a dedicated `POST /api/v1/agents/enrol` that accepts only `PRINT_AGENT` codes, sets no cookie and returns the token only in the response body. It is exempt from CSRF: CSRF protects cookie-authenticated browser requests, and this endpoint neither reads nor sets cookies, while a cross-site page cannot read its response. `POST /api/v1/devices/enrol` stays for browsers (REGISTER, KITCHEN), with CSRF, and rejects `PRINT_AGENT` codes. Update `SecurityConfig.PUBLIC_API` and the coverage test.

### 9. Void

- Requires a manager PIN entered on the register (a user with MANAGER on the venue or OWNER); the cashier session stays as it is. For each line, goods go `return_to_stock` or `waste`. Audited with the approving manager.

### 10. Manual unlock

- Owners can unlock a user's account lockout and PIN lockout; managers can unlock the PIN of users holding a role on their venue. Clears the failure count and the lock time. Audited (`user.unlocked`).
- The manager area shows which users are locked and until when.

### 11. Usage cap

- Cap = tenant override, else the plan's `sales_per_month`. The counter is incremented inside the sale transaction whether or not a receipt is printed.
- From 80%: banner for owners. At 100%: sales continue until the venue's next day change, then new sales are blocked until next month (store when the cap was reached).
- Platform admin can raise a tenant's cap (minimal endpoint, audited; the full console is Phase 4).

### 12. Frontend

- Register: cart, quantities, notes, cash payment with change, "Senza scontrino", order list with reprint, void with manager PIN, shift open/close, low-stock and sold-out live, printer-offline and cap banners.
- Manager area: suppliers, goods receipts, counts, adjustments and waste, low-stock list, printers and register-printer link, user lock status and unlock.

## Out of scope

SumUp and card payments other than manual card, separate bar and kitchen tickets, printed shift report and reports, kitchen screens, TOTP, self-service sign-up, email sending, offline selling, everything listed as out of scope in `CLAUDE.md`.

## Acceptance criteria

1. `mvn -B verify` (Testcontainers) and frontend `npm run lint && npm test && npm run build` green in CI; the Compose smoke test still passes.
2. Concurrency test: two registers sell the last unit at the same moment; exactly one succeeds.
3. Idempotency test: the same client UUID twice yields one order and one set of movements.
4. Property test: for every product, the sum of movements equals `stock_level`.
5. Database test: `UPDATE` and `DELETE` on `stock_movement` fail for `standpos_app`.
6. RLS isolation and coverage tests include every new tenant-owned table.
7. Usage-cap tests around 80%, 100% and the day change.
8. Daily order number resets at the venue's day change (test with a fixed clock and a non-midnight day change).
9. A referenced product cannot be deleted (409) and can be deactivated.
10. Unlock tests: owner and venue manager can unlock; a manager of another venue cannot; the action is audited.
11. Agent enrolment works without CSRF and sets no cookie; browser enrolment still requires CSRF and rejects `PRINT_AGENT` codes.
12. Manual checklist on the real POS80 at the oratorio: receipt layout, € sign, cut, drawer kick, reprint, agent restart with queued jobs, printer unplugged and replugged.
13. Docs: `docs/print-agent-setup.md`, `docs/guide-cashier.md` (Italian, for volunteers), `docs/guide-manager.md` (stock section, in Italian), README and `docs/development.md` updated, ADRs for every open choice, CHANGELOG `0.2.0`.

## Deliverable

Branch `phase-1` (or `phase-1/<topic>` branches merged into it), Conventional Commits, PR to `main` whose description lists: what was built, ADRs written, deviations from the spec, dependencies outside the `CLAUDE.md` list with a one-line justification, open questions for Phase 2.
