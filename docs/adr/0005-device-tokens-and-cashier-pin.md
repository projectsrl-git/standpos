# 0005 — Device tokens and cashier PIN login

- Status: accepted
- Date: 2026-10-22
- Phase: 0

## Context

Cashiers log in with a 4–6 digit PIN, only on a device enrolled for the venue. Devices are enrolled with a one-time code and must be revocable at once.

## Decision

- **Enrolment code:** 8 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no 0/O, 1/I/L), valid 10 minutes, unique, claimed atomically by `auth.claim_enrolment_code`. Input is normalised (case, spaces, dashes). Claims are rate-limited per IP.
- **Device token:** 32 random bytes, base64url. Only the SHA-256 hash is stored. Browsers receive it in an `HttpOnly`, `Secure`, `SameSite=Lax` cookie (`SP_DEVICE`, 400 days); a print agent receives it once in the response body and sends it as `Authorization: Bearer`. Bearer requests are exempt from CSRF.
- **Every request** resolves the token through `auth.find_device`; a revoked device (or a suspended tenant) resolves to nothing, so revocation is immediate.
- **PIN login is two steps:** the register lists the users who may sell there (a role on that venue, or owner, with a PIN set) and the cashier taps their name, then types the PIN. With Argon2-hashed PINs, identifying a user by PIN alone would mean one Argon2 verification per cashier per attempt, and two cashiers could not share a PIN.
- **PIN lockout** is per user: 5 wrong PINs lock that user's PIN for 15 minutes. The attempt is committed before the error is returned, so failures count.
- **A cashier session is bound to its device:** the session stores the device id; a request without the same device token invalidates the session.

## Consequences

- A stolen session cookie is useless without the device cookie of the same register.
- The cashier list exposes display names to an enrolled register of the venue only.
- Managers use their own PIN for the PIN page as well (the dev and demo bootstrap can set the owner's PIN).
