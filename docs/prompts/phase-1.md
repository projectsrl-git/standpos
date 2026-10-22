# Phase 1 — Register and stock

> Draft. Refine it after Phase 0 is merged, using what Phase 0 actually built and its open questions. Then paste it into Claude Code with `CLAUDE.md` and `docs/SPEC.md` in context.

## Goal

The oratorio can sell for cash on the register, print a courtesy receipt on the USB POS80, and run its stock: goods receipts, counts, adjustments, thresholds and automatic sold-out.

## Scope

- **Stock ledger:** `stock_movement` (append-only, enforced in the database) and `stock_level` updated in the same transaction; movement types from the spec; negative stock off by default with a venue setting.
- **Suppliers and goods receipts:** draft → posted; posting writes `receipt` movements and updates weighted average cost.
- **Counts and adjustments:** count sessions writing `count` movements for differences; `adjustment` and `waste` with mandatory reason; all audited.
- **Thresholds and sold-out:** low-stock indicator on the register, manual sold-out toggle, automatic sold-out at zero, pushed live to every register of the venue over STOMP.
- **Shifts:** open with float, close with counted cash (the printed shift report is Phase 3).
- **Sale:** cart (quantity, line note, delete), idempotent `POST /api/v1/orders` with client UUID, usage-cap check and counter increment, row locks ordered by product id, `sale` movements, order lines with name and price snapshot, daily order number per venue reset at the day-change time.
- **Payment:** cash with amount tendered and change, drawer kick; manual card as a recorded fallback.
- **"Senza scontrino":** completes sale, payment and stock movements without printing; receipt reprintable from the order list.
- **Void:** manager PIN; per line `return_to_stock` or `waste`; audited.
- **Usage cap:** 80% banner; at 100% sales continue until the venue's day change, then blocked; platform admin override.
- **Print protocol:** structured document model in `print-protocol` (text lines, alignment, bold, double size, separator, logo, cut, drawer kick), versioned.
- **Print agent v1:** Windows service (jpackage + service wrapper), enrolled with a code, outbound WSS with device token, SQLite job queue, ESC/POS via Windows spooler by printer name, code page with €, ack printed/failed, retry with backoff, heartbeat every 15 s, reprint. Receipt layout with the fixed non-fiscal line.

## Acceptance criteria (draft)

- Concurrency test: two registers sell the last unit at the same moment; exactly one succeeds.
- Idempotency test: the same client UUID twice yields one order and one set of movements.
- Property test: for every product, the sum of movements equals `stock_level`.
- Database test: `UPDATE` and `DELETE` on `stock_movement` fail for `standpos_app`.
- Usage-cap tests around 80%, 100% and the day change.
- Manual checklist on the real POS80 at the oratorio: receipt layout, € sign, cut, drawer kick, reprint, agent restart with queued jobs.
- Docs: `docs/print-agent-setup.md`, `docs/guide-cashier.md` (Italian, for volunteers), `docs/guide-manager.md` (stock section), CHANGELOG `0.2.0`.
