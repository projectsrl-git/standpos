# 0011 — Print agent service wrapper

- Status: accepted
- Date: 2026-10-26
- Phase: 1

## Context

The print agent is a Java 25 program packaged with `jpackage` as an app image (its own trimmed runtime, a `StandPOS Print Agent.exe` launcher). It must run as a Windows service on each register PC: start at boot before anyone logs in, restart after a crash, stop cleanly so the local SQLite queue and pending acks are consistent, and be installable by a volunteer with one script. `jpackage` itself cannot register a service, so a wrapper is needed. The phase prompt names Apache Commons Daemon procrun and WinSW as candidates.

## Decision

- **WinSW 2.x** (`WinSW-x64.exe`, release `v2.12.0`, MIT licence) wraps the app image's launcher: service id `StandPOSPrintAgent`, executable `%BASE%\StandPOS Print Agent.exe run`, automatic start, dependency on the `Spooler` service, restart on failure after 10 s / 30 s / 60 s (counter reset after one hour), 15 s stop timeout, its own stdout/stderr log rolled by size under `%ProgramData%\StandPOS\PrintAgent\logs`.
- On stop WinSW sends Ctrl+C to the agent, which runs the JVM shutdown hook: the agent closes the STOMP session, interrupts the printer workers (an interrupted job stays queued and resumes after the restart) and closes the queue.
- `build.ps1` downloads WinSW from the GitHub release and verifies a **pinned SHA-256** before bundling it; a mismatch or a missing pin stops the build. The executable is renamed `StandPOSPrintAgent.exe` and its XML sits next to it, as WinSW expects.
- The service runs as LocalSystem, which can print to locally installed printers and read the data directory restricted to SYSTEM and Administrators.

## Consequences

- One extra binary (about 18 MB, a self-contained .NET build) in the zip; nothing to install on the PC beyond the zip.
- The agent stays a plain console program: the same executable is used for `enrol`, `list-printers`, `test-print` and, under WinSW, `run`. Nothing in the Java code is service-specific.
- Changes to start mode, dependencies or recovery actions need `uninstall.ps1` then `install.ps1`; other XML changes apply at the next service start.
- WinSW 3 is still alpha; moving to it later only changes the XML and the pinned hash.

## Alternatives considered

- **Apache Commons Daemon procrun** (`prunsrv.exe`): mature (Tomcat uses it), but it is designed to load `jvm.dll` itself with a class path and start/stop methods (`--StartMode=jvm`), which bypasses the jpackage launcher and its runtime configuration, or to run an `exe` with a separately configured stop command. More parameters to get right on the command line, configuration kept in the registry rather than in a file next to the program.
- **NSSM**: simple and widely used, but unmaintained since 2017 (the last release is a pre-release) and often flagged by antivirus products, a real risk on donated PCs at an oratorio.
- **jpackage app image with a scheduled task "at startup"**: no third-party binary, but no stop signal (the task is killed, so no clean shutdown), weak restart-on-failure semantics, and it does not appear in the services console where whoever helps the venue would look for it.
