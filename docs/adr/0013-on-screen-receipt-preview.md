# 0013 — On-screen receipt preview

- Status: accepted
- Date: 2026-11-02
- Phase: 1 (post-demo improvements)

## Context

A register without a linked printer sells without printing, and a cashier or manager sometimes needs to see what a receipt said. The preview must look exactly like the paper, including the fixed line `Scontrino di cortesia – documento non fiscale`, which only the server generates (CLAUDE.md rule 1), and the layout must not be duplicated in the browser.

## Decision

- The server builds the receipt with the same `ReceiptBuilder` used for printing, from the order's snapshots (no reprint mark, no drawer kick), drops the final `cut` element, and lays it out with the print protocol's `PlainTextRenderer`, which now also returns styled lines (`PlainTextRenderer.Line`: text, bold, size). The agent keeps using the same class, so paper and screen share one layout.
- Width: the columns of the printer linked to the order's register, or 48 (80 mm, font A) without one.
- Endpoints, both read-only: `GET /api/v1/register/orders/{orderId}/receipt-preview` (cashier on a register of the venue) and `GET /api/v1/venues/{venueId}/orders/{orderId}/receipt-preview` (owner or venue manager). Paid and voided orders only (`409 sales.not_printable` otherwise); the order must belong to the venue (`404`).
- The browser draws each line in a monospace font on a paper `columns` characters wide; double-width lines are already half as long, so they are drawn at twice the size and fill the same width (the order number is large, as on paper).
- The register shows the preview automatically after "Conferma e mostra scontrino" when it has no printer, and on demand ("Vedi scontrino") after a sale, in its order list and in the manager's "Ordini e turni".

## Consequences

- Any change to the receipt layout appears on paper and on screen at once; the frontend has no receipt logic to keep in sync.
- A sale confirmed on a register without a printer still records no print job, so its receipt state stays "Senza scontrino" in the lists; recording "receipt wanted but no printer" would need a schema change and is left open.

## Alternatives considered

- **Send the `PrintDocument` JSON and lay it out in TypeScript:** duplicates the wrapping and alignment rules of `PlainTextRenderer`.
- **Plain text only:** loses bold and the large order number, so it would not look like the paper.
