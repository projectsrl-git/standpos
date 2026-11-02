# 0015 — Self-service sign-up and setup wizard, before email verification

- Status: accepted
- Date: 2026-11-02
- Phase: 4 (brought forward)

## Context

Until now a tenant was created only by the `--bootstrap` command. The user asked for organisations to register themselves and set up venues, registers and cashiers from zero. Phase 4 (`docs/prompts/phase-4.md`) plans sign-up with the declarations required by the spec and email verification, but the project sends no email yet. Two constraints pull against each other: sign-up should log the new owner in at once, and it must not reveal whether an email is already registered.

## Decision

1. **Endpoint.** `POST /api/v1/auth/signup` (public, CSRF as for login) takes organisation name, owner name, email, password (10–200 characters, Argon2id as everywhere) and two declarations that must be true: non-profit, and the acknowledgement that StandPOS issues only non-fiscal courtesy receipts and that the organisation alone answers for its fiscal obligations (with the pointer to its accountant). `GET /api/v1/auth/signup` tells the login page whether sign-up exists.
2. **One transaction under the new tenant.** The service generates the tenant id, then inside `TenantContext.callAs(newTenant)` writes the tenant (free plan), the owner (`app_user`), the `OWNER` membership and an audit entry `tenant.signed_up` attributed to the owner, whose `after` document records the declarations and their version (`SignupService.DECLARATIONS_VERSION`). RLS applies to every write, exactly as for bootstrap. No migration: the declarations live in the append-only audit log.
3. **Pre-tenant lookup.** The only cross-tenant read is the existing `auth.find_login(email)` (ADR 0003). A concurrent sign-up with the same email is stopped by the unique index on `app_user.email` and treated as "already registered".
4. **Same answer either way.** A registered email creates and changes nothing, and the response is the same `202 {"status":"accepted"}` as for a new one. The password is hashed before the lookup in both cases, so the response time does not tell them apart. Any earlier session in that browser is closed. Only a new owner gets a session; the frontend then reads `/auth/me` and either opens the setup wizard or shows a generic message ("if this email already has an account, log in").
5. **Rate limit.** 5 sign-ups per client address per hour (`standpos.signup.attempts-per-hour`), in memory like the other limiters; over the limit: `429 signup.rate_limited`.
6. **Switch.** `standpos.signup.enabled` (`STANDPOS_SIGNUP_ENABLED`), off by default; the `dev` and `demo` profiles turn it on. When off, the controller is not registered: both methods answer 404 and the login page hides the link. Docker Compose passes `STANDPOS_SIGNUP_ENABLED` (default `false`), so a server opts in explicitly in `deploy/.env`.
7. **Setup wizard (frontend only).** Shown to an owner whose tenant has no venue, and from the "Configurazione guidata" tab: venues, optional example catalogue, register and print agent enrolment codes, people with PINs and roles, summary. Every step calls the existing APIs (`/venues`, catalogue, enrolment codes, `/users`); progress is read back from the server, so the wizard can be left and resumed, and only the current step is remembered in the browser.

## Consequences

- **Enumeration is limited, not prevented.** The response is identical, but whoever signs up with a new email ends up logged in and whoever uses a registered one does not, so a caller can still learn that an email exists, at the cost of creating a whole organisation per probe, five per address per hour. Real protection needs email verification ("check your inbox" in both cases); until then sign-up stays off in production.
- **Deferred:** email verification and a confirmed-email flag, the email to owners about the usage cap, password reset, and abuse handling beyond the per-address limit (CAPTCHA would need a third-party service). A platform admin console to list and suspend tenants is still Phase 4.
- Tenants created by sign-up start with no venue; bootstrap still creates one.
- `SecurityConfig.PUBLIC_API` lists `/api/v1/auth/signup`; `ControllerAuthorizationCoverageIT` runs with sign-up enabled, `SignupDisabledIT` with it off.

## Alternatives considered

- **Different responses for new and registered emails:** simplest, but a free email-registered oracle.
- **No session at sign-up, log in separately:** the same leak through the login result, one more step for the owner.
- **Columns on `tenant` for the declarations:** a migration for data that is written once and read only on audit; the audit log already keeps who, when and what, append-only.
- **Building email verification now:** needs an SMTP provider and templates the project does not have yet.
