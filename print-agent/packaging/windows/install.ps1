<#
.SYNOPSIS
    Installs (or upgrades) the StandPOS print agent as the Windows service StandPOSPrintAgent.

.DESCRIPTION
    - copies the agent to %ProgramFiles%\StandPOS\PrintAgent
    - creates %ProgramData%\StandPOS\PrintAgent (configuration with the device token, queue, logs), readable only
      by SYSTEM and Administrators
    - enrols the agent with a code from the manager area (Dispositivi > Agente di stampa), unless already enrolled
    - installs and starts the service (automatic start, restart on failure)

    Run from an elevated PowerShell in the folder extracted from the zip:
        powershell -ExecutionPolicy Bypass -File .\install.ps1

.EXAMPLE
    .\install.ps1 -Server https://pos.example.org -Code ABCD2345 -Name "Agente cassa 1"
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$Server = '',
    [string]$Code = '',
    [string]$Name = '',
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'StandPOS\PrintAgent'),
    # Enrol again even if this PC already has a token (for example after the device was revoked)
    [switch]$Reenrol,
    # Install without enrolling (enrol later with the agent's 'enrol' command)
    [switch]$SkipEnrol
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ServiceId = 'StandPOSPrintAgent'
$AppName = 'StandPOS Print Agent'
$source = Join-Path $PSScriptRoot $AppName
$dataDir = Join-Path $env:ProgramData 'StandPOS\PrintAgent'
$agentExe = Join-Path $InstallDir "$AppName.exe"
$serviceExe = Join-Path $InstallDir "$ServiceId.exe"

if (-not (Test-Path (Join-Path $source "$AppName.exe"))) {
    throw "'$AppName' folder not found next to install.ps1: extract the whole zip first."
}

# 1. Stop a previous version ----------------------------------------------------------------------------------
$existing = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
if ($existing -and $existing.Status -ne 'Stopped') {
    Write-Host 'Stopping the running agent...'
    Stop-Service -Name $ServiceId
    (Get-Service -Name $ServiceId).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
}

# 2. Program files ---------------------------------------------------------------------------------------------
Write-Host "Copying the agent to $InstallDir"
New-Item -ItemType Directory -Force $InstallDir | Out-Null
robocopy $source $InstallDir /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "Copy failed (robocopy exit code $LASTEXITCODE)" }

# 3. Data directory: SYSTEM (S-1-5-18) and Administrators (S-1-5-32-544) only; SIDs work on any Windows language
New-Item -ItemType Directory -Force $dataDir | Out-Null
icacls $dataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "icacls failed on $dataDir" }
if (Get-ChildItem $dataDir -Force) {
    icacls (Join-Path $dataDir '*') /reset /T /C /Q | Out-Null
}

# 4. Enrolment -------------------------------------------------------------------------------------------------
$config = Join-Path $dataDir 'agent.properties'
$enrolled = Test-Path $config
if (-not $SkipEnrol -and (-not $enrolled -or $Reenrol)) {
    Write-Host ''
    Write-Host 'Enrolment: in the manager area open Dispositivi > Agente di stampa and create a code (valid 10 minutes).'
    for ($try = 1; $try -le 3; $try++) {
        if (-not $Server) { $Server = Read-Host 'Server URL (for example https://pos.example.org)' }
        if (-not $Code) { $Code = Read-Host 'Enrolment code' }
        if (-not $Name) {
            $Name = Read-Host "Name of this agent [Agente $env:COMPUTERNAME]"
            if (-not $Name) { $Name = "Agente $env:COMPUTERNAME" }
        }
        & $agentExe enrol --server $Server --code $Code --name $Name --home $dataDir
        if ($LASTEXITCODE -eq 0) { $enrolled = $true; break }
        Write-Warning 'Enrolment failed (see the message above). Try again with a new code.'
        $Code = ''
        if ($LASTEXITCODE -eq 2) { $Server = '' }
    }
    if (-not $enrolled) { throw 'Enrolment failed three times. Run install.ps1 again with a new code.' }
} elseif ($enrolled) {
    Write-Host "Already enrolled ($config). Use -Reenrol to enrol again."
}

# 5. Service ---------------------------------------------------------------------------------------------------
if (-not (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue)) {
    & $serviceExe install
    if ($LASTEXITCODE -ne 0) { throw "Service installation failed (exit code $LASTEXITCODE)" }
}
# On an upgrade WinSW 2.x reads StandPOSPrintAgent.xml again at the next start; start mode, dependencies and
# recovery actions are set at install time (uninstall.ps1 then install.ps1 to change them).
if ($enrolled) {
    Start-Service -Name $ServiceId
    (Get-Service -Name $ServiceId).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
    Write-Host 'The print agent service is running.'
} else {
    Write-Host 'Service installed but not started: enrol first, then run: Start-Service StandPOSPrintAgent'
}

Write-Host ''
Write-Host 'Printers on this PC (use the exact name in the manager area, Stampanti):'
& $agentExe list-printers
Write-Host ''
Write-Host "Logs: $dataDir\logs"
Write-Host "Test print: & '$agentExe' test-print --printer '<printer name>'"
