# 0004 — Roles and memberships

- Status: accepted
- Date: 2026-10-22
- Phase: 0

## Context

The spec assigns roles per venue: platform admin, owner, manager, stock clerk, cashier, kitchen. Owners act on the whole tenant (all venues, users, the SumUp connection); a platform admin acts across tenants.

## Decision

- `membership (tenant_id, user_id, venue_id, role)`. **`OWNER` is tenant-wide** (`venue_id IS NULL`); every other role is held on one venue. A check constraint enforces it.
- **`PLATFORM_ADMIN` is a flag on `app_user`**, not a membership, because it is not scoped to a tenant. No platform-admin endpoint exists in Phase 0.
- Authorisation rules live in one bean, `@authz`, used by `@PreAuthorize` on every controller method (`isOwner`, `canManageVenue(venueId)`, `hasAnyMembership`, `isRegisterDevice`, `isCashier`). Memberships are **read on each request** (cached for the request only), so removing a role takes effect immediately rather than at the next login.
- Venue-scoped endpoints are nested under `/api/v1/venues/{venueId}/…`, so the rule can check the venue from the path; the service then loads the venue through the tenant filter, so another tenant's venue is a 404.
- Users and memberships are managed by owners only. Catalogue and devices are managed by owners and the venue's managers.
- A user in several tenants lands in the oldest one at login. Switching tenant is left for later.

## Consequences

- `ControllerAuthorizationCoverageIT` fails if a controller method has no rule, and if `@PermitAll` is used outside the public list in `SecurityConfig`.
- Creating a user whose email already exists in another tenant adds that person to this tenant without changing their name or password; this reveals that the email is registered, which is acceptable for owners in Phase 0 (listed as an open question).
