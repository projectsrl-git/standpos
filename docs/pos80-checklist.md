# POS80 acceptance checklist

Manual checks on the real register PC and USB POS80 at the oratorio (Phase 1 acceptance criterion 12). Run them after [installing the print agent](print-agent-setup.md) and configuring the printer in **Stampanti** (Windows name, 80 mm, CP858). Tick each box and note anything unexpected next to it, with the agent log lines (`C:\ProgramData\StandPOS\PrintAgent\logs\agent-0.log`) when something fails.

Date: ________ Agent version: ________ Printer model / driver: ________ Tester: ________

## Setup

- [ ] `list-printers` shows the POS80 under the name configured in **Stampanti**.
      *Expected:* the same name, character for character.
- [ ] `test-print --printer "<name>"` prints the sample.
      *Expected:* `StandPOS` large and centred, `N. 42` large, rows with prices aligned on the right edge, the long item wrapped onto two lines with the price on the second, bold, double width and double height lines visibly different, a ruler of exactly one line (48 digits), then a cut and the drawer opens.
- [ ] Under **Dispositivi**, the agent shows as connected; the register does not show "stampante offline".

## Receipt layout

- [ ] Sell an order with at least three products (one with a long name), pay cash.
      *Expected:* venue header, order number large, date and time, register name, one line per product with quantity and price, total, payment method, `Scontrino di cortesia - documento non fiscale` at the bottom, centred.
- [ ] Prices are right-aligned at the paper edge and no line overflows onto the next.
- [ ] The logo, if the venue has one, prints centred and is not wider than the paper.
- [ ] The receipt prints within about 2 seconds of confirming the payment.

## Characters

- [ ] The euro sign prints as `€` (not `?`, not another symbol).
- [ ] `à è é ì ò ù` print correctly in product names (create a product such as `Caffè` or `Bibita più grande`).
- [ ] The en dash of the fixed line prints as `-`.
- [ ] Upper-case accented letters (`È`) print correctly.

## Cut and drawer

- [ ] The paper is cut after each receipt, below the last line (no text lost in the cutter).
- [ ] The cash drawer opens on a cash sale.
- [ ] The drawer does not open on reprints (unless StandPOS sends a kick for them).

## Reprint

- [ ] From the order list, press **Ristampa** on a past order.
      *Expected:* an identical receipt prints once.
- [ ] Press **Ristampa** twice quickly.
      *Expected:* two receipts, no more (each reprint is its own job).

## Agent restart with queued jobs

- [ ] Stop the service (`Stop-Service StandPOSPrintAgent`). Sell two orders.
      *Expected:* the register warns "stampante offline" within about a minute; the sales still succeed.
- [ ] Start the service again.
      *Expected:* both receipts print, in order, once each; the warning disappears.
- [ ] Stop the service while a receipt is printing (if you can time it), then start it.
      *Expected:* the receipt prints at most once more; no job is lost. Note the result.

## Printer unplugged and replugged

- [ ] Unplug the printer's USB cable (or switch it off) and sell an order.
      *Expected:* the agent retries for about a minute; then the order shows the print error as text. Note the error text: ________
- [ ] Plug the printer back in and press **Ristampa**.
      *Expected:* the receipt prints.
- [ ] Open the cover (out of paper) and sell an order; close it and press **Ristampa**.
      *Expected:* note whether the failure is reported or the job waits in the Windows queue: ________
- [ ] Check the Windows print queue: no stuck StandPOS jobs remain.

## Network down and up

- [ ] Disconnect the PC from the network (Wi-Fi off or cable out) for one minute.
      *Expected:* the register shows the red connection banner; the agent log shows reconnection attempts with growing waits.
- [ ] Reconnect.
      *Expected:* the agent reconnects within about a minute (log: `Connected to wss://…`), the warnings disappear, the next sale prints.
- [ ] Disconnect the network right after confirming a sale (if you can time it), then reconnect.
      *Expected:* the receipt prints once, after the reconnection.

## Power cycle of the PC

- [ ] Sell an order with the service stopped, then shut down and restart the PC (do not log in).
      *Expected:* the service starts by itself at boot and the pending receipt prints without anyone logging in.
- [ ] Pull the power of the PC while idle, power it on again.
      *Expected:* the service starts; the next sale prints normally; the log shows no queue errors.

## Wrap-up

- [ ] Attach the agent log of the session to the Phase 1 PR or the issue tracker.
- [ ] Note the printer's self-test code page list (photo) for the documentation.
