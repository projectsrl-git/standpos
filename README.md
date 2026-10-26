# StandPOS

Hosted, multi-tenant point of sale for non-profit associations, parish youth centres (oratori) and food stands.

- Touch register in the browser, non-fiscal courtesy receipts on an 80 mm ESC/POS printer
- Per-product stock: goods receipts, counts, thresholds, automatic sold-out
- Card payments on a SumUp Solo driven by the server (Cloud API), set up self-service by each organisation
- Free for non-profits, up to 2,000 sales per organisation per month

> StandPOS never issues fiscal documents. Each organisation is responsible for checking that it has no obligation to issue them.

## Status

**0.2.0 — Phase 1, Register and stock.** Cash and manual card sales on the register with change, "Senza scontrino", daily order numbers, reprint and voids with a manager PIN, cash drawer shifts; a per-product stock ledger with goods receipts, counts, adjustments and waste, thresholds and automatic sold-out pushed live over WebSocket; the monthly usage cap; courtesy receipts printed by the Windows print agent on the register's POS80. Built on Phase 0: multi-tenant PostgreSQL with Row-Level Security, logins, devices, catalogue, Docker Compose and CI. SumUp card payments arrive in Phase 2, bar and kitchen tickets and reports in Phase 3. See [CHANGELOG.md](CHANGELOG.md).

## Quick start (Docker Compose)

Requires Docker with Compose v2.

```bash
cp deploy/.env.example deploy/.env       # then change the passwords
docker compose -f deploy/docker-compose.yml up -d --build --wait
docker compose -f deploy/docker-compose.yml run --rm backend --bootstrap
```

Open <https://localhost> (Caddy's internal CA: accept the certificate warning or trust Caddy's root) and log in with `STANDPOS_BOOTSTRAP_OWNER_EMAIL` / `STANDPOS_BOOTSTRAP_OWNER_PASSWORD`. Then:

1. **Categorie** → create a category; **Prodotti** → create a product.
2. **Utenti** → create a cashier with a PIN (or set `STANDPOS_BOOTSTRAP_OWNER_PIN` to use the owner).
3. **Dispositivi** → generate a code for a *Cassa*; open `/enrol` and enter it: this browser is now a register.
4. On `/pin`, pick the cashier and type the PIN, open the shift with the cash float and sell.
5. For printed receipts, install the print agent on the register PC ([docs/print-agent-setup.md](docs/print-agent-setup.md)) and add the printer in **Stampanti**. For stock, tick **Gestisci magazzino** on products and post a goods receipt in **Carichi** ([docs/guide-manager.md](docs/guide-manager.md)).

On a server with a real domain, set `STANDPOS_DOMAIN` and Caddy obtains a certificate automatically; see [docs/deploy-oracle.md](docs/deploy-oracle.md) for the Oracle Cloud demo.

## Development

```bash
docker compose -f deploy/docker-compose.dev.yml up -d                 # PostgreSQL 17 on localhost:5432
./mvnw -pl backend -am spring-boot:run -Dspring-boot.run.profiles=dev  # backend on :8080, demo tenant bootstrapped
cd frontend && npm ci && npm run dev                                   # PWA on http://localhost:5173
```

Tests: `./mvnw -B verify` (Testcontainers) and `cd frontend && npm run lint && npm test && npm run build`. Details in [docs/development.md](docs/development.md).

## Repository layout

```
backend/         Spring Boot 4 (Java 25), PostgreSQL 17 with Row-Level Security
print-protocol/  Structured print document shared by the backend and the print agent
print-agent/     Windows service printing ESC/POS on the register's USB printer
frontend/        React + TypeScript PWA (register and manager area)
deploy/          Docker Compose, Caddyfile, environment template, database and backup scripts
docs/            Specification, ADRs, guides, phase prompts
```

## Documentation

| Document | Purpose |
| --- | --- |
| [docs/SPEC.md](docs/SPEC.md) | Technical specification, source of truth |
| [CLAUDE.md](CLAUDE.md) | Stack, rules, conventions and commands |
| [docs/development.md](docs/development.md) | Prerequisites, dev mode, tests, seeding |
| [docs/deploy-oracle.md](docs/deploy-oracle.md) | Demo deployment on Oracle Cloud Always Free (Arm) |
| [docs/print-agent-setup.md](docs/print-agent-setup.md) | Installing the print agent on the register PC |
| [docs/pos80-checklist.md](docs/pos80-checklist.md) | Manual acceptance checklist on the real POS80 |
| [docs/guide-cashier.md](docs/guide-cashier.md) | Guida rapida per la cassa (in Italian, for volunteers) |
| [docs/guide-manager.md](docs/guide-manager.md) | Guida per titolari e gestori, magazzino (in Italian) |
| [docs/adr/](docs/adr/README.md) | Architecture decision records |
| [docs/prompts/](docs/prompts/README.md) | One brief per development phase |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

## License

Proprietary — © Project S.r.l. All rights reserved.
