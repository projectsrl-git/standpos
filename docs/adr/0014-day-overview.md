# 0014 — Day overview of orders and shifts for managers

- Status: accepted
- Date: 2026-11-02
- Phase: 1 (post-demo improvements)

## Context

After the demo, owners and managers asked for a summary of a day's orders, the cash drawer shifts and the cashiers on each shift. The spec puts the printed shift close and the manager reports with CSV/XLSX exports in Phase 3; this is the read-only screen they need now. The spec does not say which shifts belong to a business day, since a shift may run past the day change.

## Decision

- One read-only endpoint, `GET /api/v1/venues/{venueId}/day-overview?date=YYYY-MM-DD`, for the tenant's owner or the venue's managers (`@authz.canManageVenue`). Without `date` it uses the venue's current business day (its time zone and day change time); the response carries `businessDate`, `today` and `timeZone` so the screen shows times in the venue's zone.
- **Orders:** the venue's orders whose business date is that day, newest first, with number, time, cashier and register names, payment method, total, status, void reason, the last receipt job's state and whether a receipt was printed.
- **Shifts:** every shift of the venue open at some point during the business day (opened before its end and not closed before its start), with register, cashier, opened and closed times, opening float, cash and manual card sales, expected, counted and difference. Figures are those of the whole shift, as at the register's shift close, so a shift that runs past the day change appears on both days with the same numbers, and a shift left open appears on every day until it is closed.
- **Per cashier:** the sums of that cashier's shifts listed for the day (shifts, paid and voided orders, cash, card, total, and the sum of differences of the closed ones).
- **Day totals:** from the day's orders only (paid count, voided count, cash, card, total).
- Names come from `app_user` and `device` through RLS; a user no longer in the tenant shows as a dash.

## Consequences

- Shift and cashier figures match what the cashier saw when closing the shift; they can differ from the day totals when a shift spans two business days, which the screen states.
- No new table or migration. Phase 3 can reuse `DayOverviewService` for the printed shift report and the exports.

## Alternatives considered

- **Only the shifts opened during the day:** hides a forgotten shift still open from the day before.
- **Shift figures clipped to the day:** would not match the counted cash, which is per shift.
