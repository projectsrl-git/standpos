# 0010 — Print agent enrolment endpoint

- Status: accepted
- Date: 2026-10-26
- Phase: 1

## Context

Phase 0 enrolled every device kind on `POST /api/v1/devices/enrol`, which is CSRF-protected and gives browsers their token as an `HttpOnly` cookie; for a print agent it returned the token in the body. A Windows service has no CSRF cookie to echo, and a cookie-protected endpoint that also hands out bearer tokens mixes two security models. The decision of 26 October 2026 gives the agent its own endpoint.

## Decision

- **`POST /api/v1/agents/enrol`** `{"code", "name"}` accepts only `PRINT_AGENT` codes, sets no cookie (no session, no `XSRF-TOKEN`: the CSRF token handler skips cookieless requests) and returns `{"device", "token"}`; the token is never shown again.
- **It is exempt from CSRF.** CSRF protects cookie-authenticated browser requests from cross-site forgery. This endpoint neither reads nor sets cookies, so a forged request gains nothing from the victim's cookies, and a cross-site page cannot read its response (no CORS), so it cannot obtain the token. Bearer-authenticated requests were already exempt (ADR 0005).
- **`POST /api/v1/devices/enrol`** stays for browsers (`REGISTER`, `KITCHEN`), with CSRF and the device cookie, and rejects `PRINT_AGENT` codes.
- **The claim takes the accepted kinds.** `auth.claim_enrolment_code(code, kinds[])` burns a valid code only when its kind is accepted; a valid code of another kind is reported (`claimed = false`) and stays usable, so a code typed on the wrong page is not lost. The endpoints answer `400 device.agent_code` (browser page, agent code) or `400 device.not_agent_code` (agent, browser code). Both endpoints are listed in `SecurityConfig.PUBLIC_API` and share the per-IP rate limit.

## Consequences

- The agent enrols with a plain HTTPS POST; `AgentEnrolmentIT` checks that a bare request (no cookie, no CSRF header) works and that the response sets no cookie, and that browser enrolment still requires CSRF and rejects agent codes.
- Knowing that a valid code exists and its kind is revealed to whoever holds it, which is harmless: codes are random, short-lived, single-use and rate-limited.

## Alternatives considered

- **Keep one endpoint and exempt it from CSRF:** browser enrolment would lose its CSRF protection while it sets a long-lived cookie.
- **Agent fetches a CSRF token first:** works, but makes a cookie-less client manage cookies for no security benefit.
