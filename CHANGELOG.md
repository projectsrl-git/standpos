# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-10-22

Phase 0 — Foundations.

### Added

- Maven multi-module build (Spring Boot 4.1, Java 25) with the Maven wrapper: `backend`, plus `print-protocol` and `print-agent` placeholders.
- PostgreSQL 17 schema (Flyway): plans with the free plan (2,000 sales per month), tenants, usage counters, venues, areas, users, memberships, devices, enrolment codes, routes, categories, products, append-only audit log, Spring Session tables.
- Tenant isolation twice: Hibernate `@TenantId` and Row-Level Security enabled and forced on every tenant-owned table, applied per transaction with `set_config('app.tenant_id', …, true)`; runtime role without ownership or `BYPASSRLS`; pre-tenant lookups through narrow `SECURITY DEFINER` functions.
- Owner and manager login with Argon2id, per-IP rate limiting and account lockout; sessions in PostgreSQL (Spring Session JDBC); SPA-compatible CSRF protection.
- Device enrolment with one-time codes, hashed device tokens, immediate revocation; cashier PIN login only from a register of the same venue, with PIN lockout.
- Manager API and pages for venues, users and memberships, devices, routes, categories and products; price and role changes audited.
- React + TypeScript installable PWA (Italian): login, manager area, device enrolment, PIN keypad, read-only register grid.
- RFC 9457 problem details with stable error codes; structured JSON logs with a request id; Actuator health.
- Bootstrap command (`--bootstrap`) creating the first tenant, venue and owner from the environment.
- Docker images for `linux/amd64` and `linux/arm64`, Docker Compose stack with Caddy (automatic TLS) and nightly backups, GitHub Actions CI.
- Documentation: README, development guide, Oracle Cloud demo deployment guide, ADRs 0001–0006.
- Technical specification (`docs/SPEC.md`), development instructions (`CLAUDE.md`) and phase prompts.
