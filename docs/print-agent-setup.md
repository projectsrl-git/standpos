# Print agent setup

The print agent is a small Windows service that runs on each register PC. It keeps an outbound connection to the StandPOS server (`wss://`, no inbound ports, works behind any router), receives receipts as structured documents and prints them as ESC/POS on the register's USB printer (POS80) through the Windows spooler. It keeps its own queue on disk, so a receipt received just before a reboot is printed after it.

This guide is for whoever installs it at the venue. The service wrapper is described in [ADR 0011](adr/0011-print-agent-service-wrapper.md).

## Prerequisites

- Windows 10 or 11, 64-bit, with an administrator account for the installation.
- The POS80 printer driver installed from the manufacturer's CD or website, and the printer visible in **Settings > Bluetooth & devices > Printers & scanners**. Print the driver's own test page once.
- The printer's **exact Windows name** (for example `POS-80C`). Rename it now if you want a simpler name: the name is what StandPOS uses.
- A manager account on StandPOS for the venue.
- Internet access from the PC to the StandPOS server on port 443.

The agent bundles its own Java runtime; nothing else is needed.

## Get the package

Ask for `StandPOS-PrintAgent-<version>-windows-x64.zip`, or build it on a Windows PC with JDK 25:

```powershell
# from the repository root
powershell -ExecutionPolicy Bypass -File print-agent\packaging\windows\build.ps1 -JavaHome D:\path\to\jdk-25
```

The zip lands in `print-agent\target\`. `build.ps1` downloads WinSW and verifies its pinned SHA-256; `-SkipWinSW` builds only the app image (no zip), for checking `jpackage`.

## Install

1. In the manager area open **Dispositivi**, choose **Agente di stampa** and create an enrolment code. The code is valid for 10 minutes and can be used once.
2. On the register PC, extract the whole zip (for example to the desktop).
3. Open **PowerShell as administrator** in the extracted folder and run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1
   ```

   It asks for the server URL (`https://…`), the code and a name for the agent (for example `Agente cassa 1`), then:

   - copies the agent to `C:\Program Files\StandPOS\PrintAgent`;
   - creates `C:\ProgramData\StandPOS\PrintAgent` readable only by SYSTEM and Administrators (it holds the device token);
   - enrols the agent and stores its token;
   - installs and starts the service **StandPOS Print Agent** (`StandPOSPrintAgent`), automatic start, restart on failure;
   - lists the printers Windows offers.

   Non-interactive: `.\install.ps1 -Server https://pos.example.org -Code ABCD2345 -Name "Agente cassa 1"`.

The agent now appears as connected under **Dispositivi** in the manager area.

## Configure the printer in StandPOS

In the manager area open **Stampanti** and add the printer:

| Field | Value |
| --- | --- |
| Agente di stampa | the agent you just enrolled |
| Nome stampante Windows | exactly as listed by the installer or by `list-printers` (case does not matter, spaces do) |
| Larghezza | 80 mm (48 columns) |
| Code page | `CP858` (has the € sign; `CP850`, `CP437` and `CP1252` are also supported) |

Then link the register to this printer.

## Test print

Without the server, straight from the PC (as administrator, or any user who can print):

```powershell
& "C:\Program Files\StandPOS\PrintAgent\StandPOS Print Agent.exe" list-printers
& "C:\Program Files\StandPOS\PrintAgent\StandPOS Print Agent.exe" test-print --printer "POS-80C"
```

The sample shows the € sign, `à è é ì ò ù`, bold, double width and height, an item row, the line `Scontrino di cortesia - documento non fiscale`, then cuts the paper and opens the cash drawer. `--code-page CP850` and `--columns 42` change the defaults. A printer name starting with `file:` writes the raw ESC/POS bytes to a file instead (for example `--printer file:C:\temp\receipt.bin`).

Then print a test receipt from StandPOS (a sale or "Ristampa" on an order) and run the [POS80 checklist](pos80-checklist.md).

## Demo without a receipt printer

For a demo, or to try StandPOS before the POS80 is installed, give the printer a name starting with `text:` instead of a Windows printer name, in **Stampanti**:

```
text:C:\StandPOS\scontrini.txt
```

The agent then appends every job to that file as readable text, laid out exactly as on paper (same columns, wrapping and alignment; double-width lines are spread out, `N .   4 2`), with the € sign and accented letters as they are, and a header line per job with its kind, id and time:

```
== RECEIPT 01a17738-dedb-72b3-9895-c3b75ad33182 2026-10-26T07:59:38Z
[drawer]
                  Bar oratorio
------------------------------------------------
                     ORDINE
                    N .   1
26/10/2026 08:59                         Cassa 1
------------------------------------------------
Lasagne                                    21,00
  3 x 7,00
------------------------------------------------
TOTALE                                   € 21,00
Contanti                                 € 30,00
Resto                                     € 9,00

 Scontrino di cortesia – documento non fiscale
[cut]
```

`[cut]` and `[drawer]` mark where the paper would be cut and the cash drawer opened. Open the file in Notepad (or follow it live with `Get-Content C:\StandPOS\scontrini.txt -Wait -Encoding UTF8` in PowerShell) next to the register during the demo. Everything else behaves as with a real printer: the job is acknowledged as printed, the order list shows "Stampato", "Ristampa" adds another copy. `test-print --printer text:C:\StandPOS\prova.txt` works the same way without the server.

The service runs as LocalSystem: choose a folder it can write to and the demo user can read, such as `C:\StandPOS`, not a user's profile. A `file:` printer, by contrast, writes the raw ESC/POS bytes, useful to inspect the commands themselves.

## Where things are

| What | Where |
| --- | --- |
| Program | `C:\Program Files\StandPOS\PrintAgent` |
| Configuration (server URL, device name, token) | `C:\ProgramData\StandPOS\PrintAgent\agent.properties` |
| Local queue | `C:\ProgramData\StandPOS\PrintAgent\queue.db` (SQLite; printed jobs are kept 7 days) |
| Agent log | `C:\ProgramData\StandPOS\PrintAgent\logs\agent-0.log` (5 files of 5 MB, newest is `-0`) |
| Service wrapper log | `C:\ProgramData\StandPOS\PrintAgent\logs\StandPOSPrintAgent.*.log` |

The data folder is readable by administrators only. Open an administrator PowerShell and use `Get-Content -Tail 50 -Wait` on the log. The token never appears in the logs.

The command line also accepts `--home <folder>` (or the environment variable `STANDPOS_AGENT_HOME`) to use another data folder, which is useful for testing.

## Troubleshooting

**The register shows "stampante offline".** The server has had no heartbeat from the agent for a while.
- Check that the service is running: `Get-Service StandPOSPrintAgent` (start it with `Start-Service StandPOSPrintAgent`).
- Look at the agent log. `Cannot connect to wss://…` means no internet or a wrong server URL; the agent retries by itself (1 s, 2 s, 4 s … up to every 60 s).
- If the PC was asleep, disable sleep for the register PC.

**A receipt failed ("stampa non riuscita").** The agent tried 5 times in about a minute, then reported the error, which StandPOS shows as text.
- `printer not found: X`: the Windows name in **Stampanti** does not match. Run `list-printers` and copy the name exactly.
- `printer not accepting jobs (offline or paused)`: the printer is off, unplugged, out of paper or paused in Windows (open the printer queue and choose **Resume**, untick **Use printer offline**). Fix it, then press **Ristampa**.
- If a driver always reports the printer as offline although it prints, add `printer.check-status=false` to `agent.properties` and restart the service. Jobs then wait in the Windows queue while the printer is off and print when it is back.

**Wrong characters, or `?` instead of €.** The printer's character table does not match the code page set in **Stampanti**. Most POS80 printers need `CP858`. Print the printer's self-test page (hold FEED while switching it on): it lists the code pages it supports and their numbers; StandPOS selects CP858 as table 19, CP850 as 2, CP437 as 0 and CP1252 as 16. If the printer has no CP858, choose `CP850`: the € is then printed as `EUR`. Characters a code page lacks are replaced (`–` becomes `-`, `…` becomes `...`, letters lose their accent), anything else becomes `?`.

**The paper is not cut.** Check that the model has an auto-cutter and that it is enabled in the printer's settings (some POS80 clones have a DIP switch or a setting in the driver's utility). The agent feeds 5 lines and sends a full cut (`GS V 48`); printers with a partial cutter cut partially.

**The drawer does not open.** The drawer cable must go into the printer's RJ11/RJ12 port marked DK or CASH DRAWER (not the PC). The agent sends the standard pulse on pin 2 (`ESC p 0 25 250`, with `m` sent as 48, which printers treat as 0). Some drawers are wired to pin 5: tell the developers. Check also that the drawer is not locked with its key.

**The log says the server rejected the token (HTTP 401).** The agent was revoked in the manager area, or its token is no longer valid. The agent then retries only every 5 minutes. Enrol again (below).

**Re-enrol.** Create a new code in **Dispositivi > Agente di stampa**, then in an administrator PowerShell:

```powershell
cd "C:\Program Files\StandPOS\PrintAgent"
.\"StandPOS Print Agent.exe" enrol --server https://pos.example.org --code NEWCODE1 --name "Agente cassa 1" --home C:\ProgramData\StandPOS\PrintAgent
Restart-Service StandPOSPrintAgent
```

or run `install.ps1 -Reenrol` from the extracted zip. Revoke the old device entry in the manager area if it is still listed.

**Upgrade.** Extract the new zip and run its `install.ps1`: it stops the service, replaces the program and starts it again. The enrolment and the queue are kept.

## Uninstall

In an administrator PowerShell, in the extracted folder (or a copy of `uninstall.ps1`):

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1             # keeps C:\ProgramData\StandPOS\PrintAgent
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -RemoveData # also deletes the token, queue and logs
```

Then revoke the agent under **Dispositivi** in the manager area.
