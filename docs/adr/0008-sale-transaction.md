# 0008 — The sale transaction, daily numbers and the usage cap

- Status: accepted
- Date: 2026-10-26
- Phase: 1

## Context

A cash sale is one database transaction (spec, "Point of sale flow"): usage cap check and count, stock locks and movements, order lines snapshotting name and price, payment. Retries with the same client UUID must return the existing order; the daily order number restarts at the venue's day change; the monthly cap lets venues sell until their next day change after it is reached. The spec leaves open the order of the steps, how the number is allocated, which month a sale belongs to, how the grace period is measured for a tenant with several venues, and how shifts, "Senza scontrino" and voids fit in.

## Decision

- **Idempotency.** `customer_order (tenant_id, client_order_id)` is unique. `POST /api/v1/orders` first looks the UUID up (a replay answers `200` with the stored order; `201` for a new one). A concurrent duplicate fails on the unique key when the order row is flushed, early in the transaction, and is answered by re-reading the committed order. A UUID used by another register is refused (`409 sales.client_id_reused`). The register generates a new UUID whenever the cart changes and keeps it across retries of the same cart.
- **Order of the steps, also the lock order:** open shift check → products (active, not sold out by hand) → usage counter upsert → daily number upsert → order insert (flush) → lines → stock levels locked `FOR UPDATE` ordered by product id → stock check → `SALE` movements → payment → receipt or drawer job. Every sale takes the same locks in the same order and voids, receipts and counts take only stock locks, so there are no deadlocks. The usage counter row serialises the tenant's sales for the length of a sale (a few milliseconds), far above the design load of 2,000 orders per venue per day.
- **Daily number.** `order_day_counter (tenant, venue, business_date)` is incremented with `INSERT … ON CONFLICT DO UPDATE … RETURNING`; a rolled-back sale gives its number back. The business date is the venue-local date, minus one day before the venue's day change time (`BusinessDay`); `(venue, business_date, daily_number)` is unique.
- **Usage cap.** The counter is the calendar month of the sale in the venue's time zone (`usage_counter.month`), incremented in the sale transaction whether or not a receipt prints; a sale refused or rolled back is not counted. The first sale that reaches the cap stores `cap_reached_at`. A sale is refused with `409 usage.cap_reached` when the month's sales are already at or above the cap and the selling venue's first day change after `cap_reached_at` has passed: each venue keeps selling until its own next day change. The cap is the tenant's override, else the plan's `sales_per_month`. `warned_at` marks the 80% threshold; Phase 1 shows the owner a banner, the email is Phase 4. The platform admin sets the override with `PUT /api/v1/admin/tenants/{id}/sales-cap` (audited in that tenant); months whose sales fall below the new cap lose `cap_reached_at` and unblock.
- **Shifts.** Selling needs an open shift on the register, opened by the cashier who sells (`409 shift.not_open` / `shift.other_cashier`). One open shift per register (partial unique index). The shift closes with the counted cash; the expected cash is the float plus the cash of the shift's paid orders (voided orders gave their cash back). It can be closed by the cashier who opened it or by an owner or manager of the venue logged in on the register.
- **Payments in Phase 1:** cash (amount tendered, change) and manual card (recorded, not verified, `needs_reconciliation = true`). SumUp is refused until Phase 2.
- **Receipt and drawer.** With "Conferma" the receipt job is queued in the sale transaction, with a drawer kick first for cash. With "Senza scontrino" nothing is printed, but a cash sale still queues a `DRAWER` job (kick pulse only, no paper), since the cashier has to open the drawer. A register without a printer still sells; the receipt can be printed later from the order list once a printer is linked.
- **Void.** Only a paid order of the venue, with a reason, approved by the PIN of a manager of the venue or the owner entered on the register (`PinLoginService.verifyApprover`: same lockout as the PIN login, failures committed before the error, cashier session untouched). Every line gets a disposition (see ADR 0007), the payment becomes `REFUNDED`, a cash void kicks the drawer, and the audit entry names the approving manager.

## Consequences

- A flaky connection can never sell twice; the register can safely retry until it gets an answer.
- Sales of one tenant are serialised on the counter row; scaling to much larger tenants would move the counter to a batched or sharded design (the cap only needs to be exact at the threshold).
- The cap is per tenant, while the grace period is per venue: a festival venue with a late day change sells a little longer than the bar.
- Shift hand-over between volunteers means closing and opening a shift (listed as an open question for Phase 3, with the printed shift report).

## Alternatives considered

- **Advisory lock on the client UUID:** avoids the failed insert but adds a second locking mechanism; the unique key is already the source of truth.
- **A PostgreSQL sequence per venue and day:** sequences are not transactional (gaps on rollback) and need DDL per day.
- **Grace until the tenant's earliest day change:** simpler but stops a venue before its own day ends, which the spec wants to avoid.
