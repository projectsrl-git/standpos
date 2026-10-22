# Phase 3 — Tickets and reports

> Draft. Refine it after Phase 2 is merged. The oratorio pilot starts when this phase is done.

## Goal

Optional separate bar and kitchen tickets, a printed shift close that tells the cashier how much cash must be in the drawer, and the reports the treasurer needs.

## Scope

- **Ticket mode per venue:** receipt only (default) or receipt plus one ticket per route present in the order, each cut separately; route from category with product override; tickets show order number, time, that route's lines and notes, no prices.
- **Shift close:** expected vs counted cash, difference, SumUp payments confirmed, manual card payments, voids and returns with approving manager; printed on the POS80 and stored as PDF.
- **Manager reports:** sales by product, category, route, hour, day, venue, event; stock on hand, below threshold, receipts, waste, valuation at average cost, margin; CSV and XLSX export (Apache POI).
- **SumUp reconciliation:** the day's transactions from the Transactions API against StandPOS payments, flagging mismatches either way.
- **End-of-event statement** for the treasurer.

## Acceptance criteria (draft)

- Report totals reconcile with orders, payments and movements in tests built on a seeded event.
- Shift close printed on the real POS80 and checked by hand against a counted drawer.
- Pilot checklist for the oratorio (hardware, network, printer, Solo, users, catalogue, stock opening count) in `docs/pilot-checklist.md`.
- Docs: `docs/guide-manager.md` (reports), CHANGELOG `0.4.0`.
