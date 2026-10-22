# StandPOS

Hosted, multi-tenant point of sale for non-profit associations, parish youth centres (oratori) and food stands.

- Touch register in the browser, non-fiscal courtesy receipts on an 80 mm ESC/POS printer
- Per-product stock: goods receipts, counts, thresholds, automatic sold-out
- Card payments on a SumUp Solo driven by the server (Cloud API), set up self-service by each organisation
- Free for non-profits, up to 2,000 sales per organisation per month

> StandPOS never issues fiscal documents. Each organisation is responsible for checking that it has no obligation to issue them.

## Status

Specification complete; development not started. Next: [Phase 0 — Foundations](docs/prompts/phase-0.md).

## Documentation

| Document | Purpose |
| --- | --- |
| [docs/SPEC.md](docs/SPEC.md) | Technical specification, source of truth |
| [CLAUDE.md](CLAUDE.md) | Stack, rules and conventions for development with Claude Code |
| [docs/prompts/](docs/prompts/README.md) | One brief per development phase |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

Quick start, development and deployment guides are added in Phase 0.

## Planned layout

```
backend/         Spring Boot 4 (Java 25), PostgreSQL 17 with Row-Level Security
print-protocol/  Shared print document model
print-agent/     Windows service printing ESC/POS on the register's USB printer
frontend/        React + TypeScript PWA (register and manager area)
deploy/          Docker Compose and Caddy
docs/            Specification, prompts, ADRs, guides
```

## License

Proprietary — © Project S.r.l. All rights reserved.
