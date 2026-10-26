# 0009 — WebSocket/STOMP security

- Status: accepted
- Date: 2026-10-26
- Phase: 1

## Context

Registers receive stock, sold-out and printer changes live, and print agents receive jobs and send acknowledgements and heartbeats, over WebSocket/STOMP at `/ws` (spec, Phase 1 §4 and §8). Browsers authenticate with the existing session cookie (manager or cashier) or the register's device cookie; the agent sends its bearer device token on the handshake. Subscriptions must be limited to the caller's venue, the tenant context must hold while frames are handled, and a revoked device must lose its connection.

## Decision

- **Handshake through the HTTP security chain.** `/ws` requires authentication like `/api/**`: the `DeviceAuthenticationFilter` resolves the device token (cookie or bearer) and the session principal as for any request, and the WebSocket session takes that principal (USER, CASHIER or DEVICE). The handshake interceptor copies the resolved device (kind, venue) into the session attributes. No token travels in a URL or in STOMP headers.
- **Same origin for browsers.** Spring's origin check accepts only same-origin handshakes (plus `standpos.websocket.allowed-origins`, the Vite dev server in the `dev` profile). Requests without an `Origin` header (the agent) are not browser requests and pass. With `SameSite=Lax` cookies and the origin check, a cross-site page cannot open a socket in a user's name, so **no CSRF token is required on STOMP `CONNECT`**; Spring Security's `@EnableWebSocketSecurity` (which would also demand one from the agent) is not used.
- **Per-frame authorisation, deny by default** (`StompAuthorization`, an inbound channel interceptor):
  - `CONNECT` needs the handshake principal;
  - `SUBSCRIBE /topic/venues/{venueId}/stock|printers|print-jobs`: a cashier or device of that venue, or a user holding any role on it (owners: every venue of their tenant);
  - `SUBSCRIBE /user/queue/print-jobs` and `SEND /app/agent/heartbeat|ack`: print agents only, re-checked against revocation on every frame;
  - anything else is refused with a short STOMP `ERROR` frame (`Access denied`) and the connection is closed. The message must stay short: Spring's default would echo the whole session header dump to the client.
- **Tenant context.** `TenantChannelInterceptor` (an executor channel interceptor) sets `TenantContext` from the frame's principal on the handling thread and clears it afterwards, so `@MessageMapping` handlers and their transactions run in the sender's tenant, as `TenantContextFilter` does for HTTP.
- **Server-to-client messages** are sent after the causing transaction commits (`@TransactionalEventListener`) and carry ids and states only: no names of people, no amounts.
- **Revocation** closes the device's open WebSocket sessions after the revoking transaction commits (`WebSocketSessions`), in addition to the per-frame check.
- **Ordering.** Frames of one session are not processed in order (Spring's `preserveReceiveOrder` swallows `ERROR` frames, so it is off). Job delivery therefore does not rely on the subscription being registered before the agent's next frame: jobs are sent only to an agent whose subscription the user registry shows, one second after it subscribes, on every heartbeat, and right after a new job commits.
- **Presence** (online/offline from heartbeats) is kept in memory in the single backend instance.

## Consequences

- A register sees only its own venue's events; a print agent only its own jobs; another tenant's venue id is refused like any other.
- A browser session that expires keeps its socket until it closes; the socket only receives venue states, and the next HTTP call fails as usual. Spring Session's WebSocket integration (keeping the session alive or closing on expiry) can be added if it matters.
- Scaling to several backend instances needs an external broker relay and a shared presence store.

## Alternatives considered

- **Token in the STOMP `CONNECT` headers:** works for the agent but means a second authentication path for browsers, which already have cookies.
- **SockJS fallback:** all supported clients (Chromium, the JDK agent) speak plain WebSocket.
- **Server-Sent Events for registers:** simpler for one-way events but the agent needs two-way messages, so one protocol for both is simpler overall.
