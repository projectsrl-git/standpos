# 0007 — Stock ledger and valuation

- Status: accepted
- Date: 2026-10-26
- Phase: 1

## Context

Stock is an append-only ledger (`stock_movement`) with the current quantity materialised in `stock_level` in the same transaction (CLAUDE.md rule 4). The spec asks for a weighted average cost from goods receipt unit costs, without floating point, and for voided lines to go "back to stock or to waste", which the movement types must express. It leaves open the rounding rule, what a sale removes from the value, how a void to waste is recorded and how sold-out interacts with the manual flag.

## Decision

- **Quantity and value side by side.** `stock_level` holds `on_hand` (integer units) and `value_cents` (bigint, never negative). Every movement stores its signed `quantity`, its signed `value_cents` and `on_hand_after`, so the sum of a product's movements equals its level in quantity *and* in value (the property test checks both).
- **Average cost** is derived, never stored: `value_cents / on_hand`, rounded down; zero without stock.
- **Incoming units** (receipt, positive adjustment or count) add `quantity × unit cost` when the receipt line has a cost, otherwise `quantity × average cost`.
- **Outgoing units** (sale, waste, negative adjustment or count) remove their proportional share of the value, `round(value × quantity / on_hand)` half up in integers; when they take the last units (or more), they take all the remaining value. The value therefore reaches exactly zero with the stock, and rounding never accumulates.
- **With negative stock** (venue option): outgoing units beyond zero take no value, and the value stays at zero until goods come in again. The next receipt's value is taken as is: the valuation is approximate while stock is negative, which is accepted for events where receipts are posted after the fact.
- **Reversals give back exactly what the sale took.** A void reads the order's `SALE` movements and splits each one's value between the returned and wasted units (half up, remainder to the waste part):
  - goods back on the shelf: `RETURN_TO_STOCK` (+units, +their share of the value);
  - goods thrown away: `SALE_REVERSAL` (+units, +share) followed by `WASTE` (−units, −the same share).
  So "returns" and "waste" are separate types in reports, net sales are `SALE + SALE_REVERSAL + RETURN_TO_STOCK`, and the stock never moves twice for the same units. `SALE_REVERSAL` alone is also what a failed SumUp payment will write in Phase 2.
- **Only tracked products have stock.** Movements, levels, receipt lines and counts are refused for products without `track_stock` (`400 stock.product_not_tracked`). A product whose tracking was switched off after a sale is skipped by its void.
- **Counts compare with the quantity at posting time**, not with the `expected` snapshot taken when the session opened, because sales may happen in between; the line stores the difference actually posted. Products left blank are not touched.
- **Sold-out is derived.** The register shows a product as sold out when the manager's manual flag (`product.sold_out`) is set, or when it is tracked, has no stock left and the venue does not allow negative stock. The automatic part turns back on by itself when goods arrive; the manual flag stays until a manager clears it. No sold-out column is written by sales.
- **One writer.** `StockLedger` is the only code that writes `stock_level` and `stock_movement`, in plain JDBC with explicit `SELECT … FOR UPDATE` ordered by product id and an explicit `tenant_id` filter (the application-level line in front of RLS, since Hibernate's `@TenantId` does not cover JDBC). Read-only JPA entities serve the queries. Missing level rows are created at zero with `INSERT … ON CONFLICT DO NOTHING` before locking.
- **Append-only in the database:** no `UPDATE`/`DELETE` granted to `standpos_app` and a trigger rejecting them, as for `audit_log`. A posted goods receipt and its lines are also protected by triggers.

## Consequences

- Stock value, waste value and cost of goods are exact sums of integers, ready for the Phase 3 reports.
- A wrong receipt cannot be edited once posted: it is corrected with an adjustment or a count, which is visible in the ledger.
- The register needs no stock computation: availability comes from the level and two flags.

## Alternatives considered

- **Store the average cost and multiply on the way out:** rounding drifts and the value does not reach zero with the stock.
- **FIFO lots:** more accurate margins but needs lots, which the spec leaves out of v1.
- **A void to waste as a single `WASTE` movement:** the stock would be decremented twice (sale and waste) for the same units.
- **A stored automatic sold-out flag flipped by sales:** it can drift from the stock and needs extra writes on every sale and receipt.
