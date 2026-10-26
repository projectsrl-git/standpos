# 0012 — Print job pipeline

- Status: accepted
- Date: 2026-10-26
- Phase: 1

## Context

The server builds structured documents and the agent renders them (spec, "Printing"). Jobs must stay queued while the agent is offline, survive a reboot on the agent, be acknowledged as printed or failed, and drive the register's "stampante offline" warning and "Ristampa". The spec lists job kinds receipt, ticket and report, and leaves open delivery guarantees, expiry and how a register finds its printer.

## Decision

- **Protocol** (`print-protocol`, version 1): `PrintDocument(version, elements)` with typed elements (`text` with alignment, bold and size; `row` for an item and its price; `separator`; `feed`; `logo` as base64 PNG; `cut`; `drawer`), wrapped in `PrintJob(jobId, kind, printer{windowsName, columns, codePage}, document)`; answers are `JobAck(jobId, PRINTED|FAILED, attempts, error)`; liveness is `AgentHeartbeat` every 15 s. `PlainTextRenderer` holds the layout rules (wrapping, alignment, rows) shared by server tests, previews and the agent. An agent refuses documents of a newer version.
- **Kinds:** `RECEIPT`, `TICKET`, `REPORT` as in the spec, plus `DRAWER` (kick pulse only: "Senza scontrino" with cash, cash voids) and `TEST` (the manager's test page).
- **Printers and registers.** `printer` (venue, agent device, Windows name, 58 or 80 mm, code page `CP858` by default for the € sign) is driven by one agent; a register points at its printer with `device.printer_id`. 80 mm means 48 columns in font A, 58 mm 32.
- **Server queue** (`print_job`): `QUEUED` → `SENT` (delivered, `attempts` counts deliveries) → `PRINTED` or `FAILED` from the ack. Jobs are delivered when queued (if the agent is subscribed), one second after the agent subscribes, and on each heartbeat; a `SENT` job without an answer for 30 s is sent again. Delivery is therefore at-least-once and the agent deduplicates by job id (a job it already printed is acknowledged again without printing).
- **Expiry.** A job still pending 12 hours after it was queued fails with "expired" instead of printing: a receipt from last night is useless and confusing at the next opening.
- **Receipt state.** A `PRINTED` receipt sets `customer_order.receipt_printed`; the order list shows the last receipt job's state, and a reprint of a printed receipt carries the word `RISTAMPA`. Finished jobs and agent online/offline changes are pushed to the venue's registers (`/topic/venues/{id}/print-jobs`, `/printers`); a failed receipt shows a banner with "Ristampa".
- **Agent side** (ADR 0011 for the service): an SQLite queue stores each job before printing and each outcome together with its pending ack, retries a failed print with backoff (2, 5, 15, 30 s, five attempts) before acknowledging `FAILED`, and transliterates characters the code page lacks (the fixed receipt line has an en dash).

## Consequences

- No receipt is lost while the agent or the network is down, and none prints twice in normal operation; a job interrupted mid-print by a service stop may print once more after the restart (agent open issue).
- Out of paper is reported by the Windows spooler only on some printers; whether the POS80 reports it is part of the manual checklist (`docs/pos80-checklist.md`).
- Separate bar and kitchen tickets (Phase 3) reuse the same pipeline with kind `TICKET`.

## Alternatives considered

- **Raw ESC/POS bytes from the server:** the layout would live in one place too, but code pages, printer quirks and the logo raster belong next to the printer.
- **Exactly-once delivery with per-job ack timeouts on the broker:** the simple broker has no such feature, and idempotent agents make at-least-once enough.
