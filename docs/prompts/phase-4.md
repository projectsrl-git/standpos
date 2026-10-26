# Phase 4 — SaaS onboarding

> Draft. Refine it after the oratorio pilot, using what the pilot taught.

## Goal

Other associations can sign up and run StandPOS without the author's help, within the free plan's limits.

## Scope

- **Self-service sign-up:** organisation details, non-profit declaration, acknowledgement that receipts are non-fiscal and that the tenant is responsible for its own fiscal obligations; email verification.
- **Usage alerts:** 80% banner already exists; add the email to owners; monthly reset job.
- **Platform admin console:** tenants, status, usage, cap override, audited support access.
- **TOTP** for owners and managers (optional per user, enforced for platform admins).
- **Refunds through the SumUp API:** first verify in SumUp's public documentation that the refund endpoint works with a self-service API key; if it does not, keep refunds in the SumUp app.
- **Kitchen screens** (optional): route-filtered live tickets over STOMP, ready/served states.
- **Legal and production:** DPA template for tenants, privacy notice, terms of use; production deployment in the ISO 27001 datacenter with WAL archiving and a restore drill.
- **Deferred from Phase 0:** tenant switcher for users who belong to several tenants (today they land in the oldest one); password change and reset for owners and managers (needs email); invitation flow instead of silently adding a user whose email already exists in another tenant (today that reveals the email is registered); shared rate-limiter store if the backend is ever scaled out.

## Acceptance criteria (draft)

- A new association completes sign-up, catalogue, device enrolment and SumUp connection using only the in-app guides.
- Cap and alert behaviour covered by tests, including month rollover.
- Docs: owner onboarding guide, platform admin runbook, production deployment guide, CHANGELOG `1.0.0`.
