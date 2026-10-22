# Phase 2 — SumUp Solo

> Draft. Refine it after Phase 1 is merged. Re-read the SumUp documentation linked in `docs/SPEC.md` before starting: APIs change, and the spec records them as of October 2026.

## Goal

Card payments on a SumUp Solo driven by the server, set up by each tenant alone, verified with SumUp before any order is marked paid.

## Scope

- **Connection wizard (owner):** merchant code, API key, affiliate app id and affiliate key; Italian step-by-step instructions with screenshots of the SumUp dashboard; validation by listing the account's readers; keys encrypted with AES-256-GCM; status and last check shown; replace and disconnect.
- **Reader pairing:** guided page (log out of the Solo, Connections → API → Connect, enter the 8–9 character code within 5 minutes), name the reader, assign it to a register; list, rename, unpair (API delete plus the manual step on the device).
- **Checkout:** order in `awaiting_payment` with stock held; `POST /v0.1/merchants/{merchant}/readers/{reader}/checkout` with amount in cents, description, affiliate data and `return_url` carrying a one-time token; store `client_transaction_id`.
- **Result:** webhook endpoint `/webhooks/sumup/{tenant}/{token}` accepts only a matching token; every result is confirmed through the Transactions API by `client_transaction_id` (status and amount); idempotent; polling fallback after 90 s; failures release stock with `sale_reversal`.
- **Cancel:** terminate endpoint while the reader waits for card or PIN; UI reflects that there is no confirmation.
- **Register UI states:** Inviato al lettore → In attesa della carta → Pagato / Rifiutato (with SumUp's reason as text, retry, other method). One reader per register; a second checkout during the 60 s window is prevented in the UI and handled on the server.
- **Fallback:** cash or manual card when SumUp is unreachable; manual card flagged for reconciliation.

## Acceptance criteria (draft)

- WireMock contract tests for pairing, checkout, webhook, verification, polling, terminate, duplicate and out-of-order events, amount mismatch, unknown `client_transaction_id`.
- End-to-end run against a SumUp sandbox merchant and the Virtual Solo, documented step by step.
- **Gate:** test on a real Solo, including the open question on card fallback when SumUp is unreachable; record the outcome in the spec.
- Docs: `docs/guide-sumup-setup.md` (Italian, for owners), CHANGELOG `0.3.0`.
