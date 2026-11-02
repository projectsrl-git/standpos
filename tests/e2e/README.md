# StandPOS end-to-end suite (PowerShell)

Black-box tests of a running StandPOS through its REST API, written for **Windows PowerShell 5.1** with no extra
modules. One command runs every area in order, prints a pass/fail summary and exits non-zero on failure. On the way
it fills the target with a plausible oratorio bar: catalogue, suppliers, stock and a handful of sales.

```powershell
.\tests\e2e\Run-All.ps1 -Target local     # the local demo, http://127.0.0.1:8088
.\tests\e2e\Run-All.ps1 -Target cloud     # the cloud demo, URL read from cloud-url.txt
```

If script execution is disabled on the PC, run it through a one-off bypass:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\e2e\Run-All.ps1 -Target local
```

> **It writes to the target.** The suite logs in as the tenant owner and adds data to that tenant (by default the
> demo tenant and its venue "Bar oratorio"). Do not point it at a production tenant.

## Parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Target local\|cloud` | `local` | `local` = `http://127.0.0.1:8088` (Caddy of the local demo); `cloud` = the first line of `-CloudUrlFile` |
| `-BaseUrl` | | Any other base URL; overrides `-Target` |
| `-EnvFile` | `D:\SVILUPPO\standpos-demo\deploy.env` | `KEY=value` file with `STANDPOS_BOOTSTRAP_OWNER_EMAIL`, `STANDPOS_BOOTSTRAP_OWNER_PASSWORD`, `STANDPOS_BOOTSTRAP_OWNER_PIN` (and optionally `STANDPOS_BOOTSTRAP_VENUE_NAME`) |
| `-CloudUrlFile` | `D:\SVILUPPO\standpos-demo\cloud-url.txt` | Written by the cloud deployment; if it does not exist yet the run stops with a clear message |
| `-VenueName` | `STANDPOS_BOOTSTRAP_VENUE_NAME`, else the first venue | Venue under test |
| `-Only` | all | Wildcards on script names, e.g. `'0[1-4]*'`; later scripts need the data of earlier ones |

Exit codes: `0` all steps passed (some may be skipped, with the reason in the summary), `1` a step failed, `2` setup error (no URL, env file, or the target's
`/actuator/health` is not `UP`). Secrets from the env file are never printed.

## Layout

```
tests/e2e/
  Run-All.ps1            runner: target selection, shared context, summary, exit code
  lib/StandposE2E.psm1   HTTP client, CSRF, problem details, assertions, results
  data/demo-data.json    the demo catalogue, suppliers, goods receipts and sales (UTF-8, prices in cents)
  scripts/NN-Area.ps1    one script per area, run in name order
```

Each script takes `-Ctx` (a hashtable shared by the run: base URL, owner client, venue, ids created so far) and is
a list of `Test-Step 'name' { ... }` blocks. A failing step stops its script and the remaining scripts are skipped,
since they build on its data. Keep state between steps in `$Ctx` or a script hashtable such as `$S`: each block runs
in its own scope.

The helper module:

- `New-ApiClient` keeps a cookie session (`WebRequestSession`): the session cookie, `XSRF-TOKEN` and the register's
  `SP_DEVICE` cookie. A client created with `-BearerToken` acts as a print agent (no cookies, no CSRF).
- `Invoke-Api` sends JSON as UTF-8, adds `X-XSRF-TOKEN` from the cookie on state-changing requests, and returns
  `Status`, `Body`, `Problem` (the RFC 9457 problem details, with their stable `code`) and `Headers`. It throws only
  when `-Expect` is given and the status differs.
- The backend sets its cookies `Secure`. On an `http://` base URL (the local demo behind Caddy on 127.0.0.1) the
  client drops that flag on its side so the cookies go back, as a browser does for localhost.
- `Invoke-Login` waits out the login rate limit (429 `auth.rate_limited`, 10 attempts a minute per IP), so the
  suite can be run several times in a row.
- `Assert-Equal`, `Assert-True`, `Assert-NotNull`, `Assert-Status`, `Assert-Problem` (status, `code` and the RFC 9457
  members).
- `Skip-Step` records a step as skipped with its reason (a feature the target has switched off or rate-limited);
  `Assert-ReceiptPreview` checks a server-built receipt preview against its order.

## What is covered

| Script | Area |
| --- | --- |
| `01-Auth` | CSRF cookie and header, 401 when anonymous, login refused without CSRF, validation errors, unknown email and wrong password (a single failure: no lockout) followed by a successful login, `/auth/me`, logout |
| `02-Venues` | venue list, an event venue created or reused and updated, unknown time zone, validation, 404 |
| `03-Users` | PIN-only cashier and manager of the venue, a volunteer of the event venue, a manager of the event venue with email and password, PIN reset and unlock on re-runs, duplicate membership, role without venue, invalid PIN, the venue's people list |
| `04-Catalogue` | routes Bar and Cucina, categories Bibite, Caffetteria, Panini, Dolci with default route, 16 products in integer cents (9 with stock tracking), invalid colour, negative price, category not empty, route in use, deactivate and delete an unused product |
| `05-Stock` | suppliers, goods receipts (draft, edit, post, stock up by exactly the quantities, total cost), empty and untracked receipts, posted receipt immutable, supplier in use, adjustment, waste, refused movement types and quantities, insufficient stock, stock count (counted, posted, `COUNT` movement), movements, low-stock list |
| `06-Devices` | enrolment codes, browser enrolment of a register with an HttpOnly `SP_DEVICE` cookie, `/devices/current`, wrong-kind codes on both endpoints (not burnt), used codes, print agent enrolment on `/api/v1/agents/enrol` with a bearer token, rename, printer of the agent (invalid width, invalid agent), register-to-printer link, test page, printer in use |
| `07-Register` | cashiers list, PIN login (wrong PIN, user of another venue), cashier kept out of the manager area (day overview included), shift open/close with counted cash and difference, register status and grid, cash sale with change and line snapshots, idempotent retry with the same client UUID (200, nothing sold twice), the same UUID from a second register (409), "senza scontrino" and later receipt, manual card with a line note, refused sales (SumUp, tender, quantity, stock), sold-out by hand, void approved by a manager PIN (wrong PIN, cashier as approver, missing lines, back to stock vs waste, not voidable twice), demo sales paid with the cash dialog's notes and coins (e.g. 12,00 with 10 + 2) and change in integer cents, receipt preview from the register and the manager area (fixed non-fiscal line, order number, line amounts, total, cash and change or card; voided orders too; 404 for unknown orders or another venue, 403 for a cashier on the manager path), a second register without a printer where a sale "with receipt" lists as not printed (known, accepted behaviour) and the preview stands in for it, orders of the day |
| `08-DayOverview` | "Ordini e turni" (`/day-overview`): default date is today in the venue's time zone and day change, explicit date, every order of the run with total, payment, status, cashier and register, day totals grown by exactly the run's cash and card sales and agreeing with the order rows, the run's shifts with float, sales, expected and counted cash and difference, totals per cashier adding up their shifts, an empty day, malformed date, 403 for the manager of another venue (who sees their own venue) |
| `09-Usage` | the month's sales counter grew by exactly the orders created, owner cannot change the cap, the run's devices are revoked and lose access |
| `10-Signup` | self-service sign-up (ADR 0015): public availability and declarations version, CSRF required, missing declarations and short password refused (400 validation) with no session, a new organisation `E2E Associazione <run>` with an invented `.test` email signed up and logged in as owner, the new tenant empty and blind to the demo tenant (venues, users, suppliers, usage, the demo venue's id and a demo receipt), the setup wizard through the APIs it calls (a permanent venue and a festival, the starter catalogue, register and print agent enrolment codes, a cashier and a manager with PINs, the summary it reads back), a register of the new tenant enrolled with its code, PIN login, shift, a sale paid 5 + 2 EUR, receipt preview and day overview, then a second sign-up with the same email answering exactly like a new one without a session or any change |

**Not covered** (needs other tools or a later phase): the WebSocket/STOMP channel (live stock events, print job
delivery and acknowledgements by an agent: the queued jobs stay `QUEUED` because no agent of the suite connects),
the usage cap reaching 80%/100% (needs a platform admin or 2,000 sales), account and PIN lockout after repeated
failures (it would lock the demo owner for 15 minutes), SumUp card payments (Phase 2), the PWA user interface
(the cash dialog's note and coin buttons themselves: through the API a cash payment is still `tenderedCents`),
orders of another tenant (the suite has one tenant's credentials; `RlsIsolationIT` covers it). The backend's own
integration tests (`./mvnw -B verify`) cover these at a lower level.

## Data and re-runs

Re-running is safe: routes, categories, products, suppliers, users, the event venue and the printer are looked up
by name and reused. Every run adds two posted goods receipts, an adjustment, a waste and a stock count, two shifts
and 14 orders (one of them voided), which count towards the tenant's monthly cap of 2,000 sales.

`10-Signup` creates **one new tenant per run** (`E2E Associazione <run>`, owner `e2e-associazione-<run>@standpos.test`,
with two venues, the starter catalogue, two people and one sale); there is no API to delete a tenant. Sign-up allows
5 attempts per client address per hour and a run uses 2, so about two full runs an hour per target: beyond that the
server answers `429 signup.rate_limited` and the sign-up steps are reported as **skipped** with the reason, as they
are when sign-up is switched off (`GET /api/v1/auth/signup` answers 404). Skipped steps do not fail the run.

Test-only entities are marked with **E2E**: users `E2E Cassiere`, `E2E Responsabile`, `E2E Volontario festa` and
`E2E Responsabile festa` (the only one with an email, `e2e-responsabile-festa@standpos.test`), the venue
`E2E Festa dell'oratorio`, the devices `E2E Cassa ...`, `E2E Cassa 2 ...` and `E2E Agente ...`, the printer
`E2E Stampante`, goods receipts `E2E-DDT-...` and reasons and notes starting with `E2E`. Their PINs and the event
manager's password are fixed test values in `scripts/03-Users.ps1`; PINs are reset at the start of every run. The demo catalogue itself (Bar, Cucina,
Bibite, "Panino con salamella", ...) has no prefix: it is meant to stay as demo data.

At the start of a run, draft goods receipts and stock counts left by an interrupted run and still-active `E2E`
devices are removed or revoked; at the end the run revokes its own register and agent.

Scripts are kept ASCII-only so that Windows PowerShell 5.1 reads them correctly without a byte order mark; accented
names live in `data/demo-data.json`, read as UTF-8.
