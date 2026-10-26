<#
.SYNOPSIS
    Stops and removes the StandPOS print agent service and its program files.

.DESCRIPTION
    The data directory (%ProgramData%\StandPOS\PrintAgent: device token, local queue, logs) is kept unless
    -RemoveData is given, so that a reinstall keeps its enrolment. Revoke the device in the manager area as well
    when the PC leaves the venue.

    Run from an elevated PowerShell:
        powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 [-RemoveData]
#>
#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'StandPOS\PrintAgent'),
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ServiceId = 'StandPOSPrintAgent'
$dataDir = Join-Path $env:ProgramData 'StandPOS\PrintAgent'
$serviceExe = Join-Path $InstallDir "$ServiceId.exe"

$service = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceId
        (Get-Service -Name $ServiceId).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }
    if (Test-Path $serviceExe) {
        & $serviceExe uninstall
    } else {
        sc.exe delete $ServiceId | Out-Null
    }
    Write-Host 'Service removed.'
}

if (Test-Path $InstallDir) {
    Remove-Item -Recurse -Force $InstallDir
    Write-Host "Removed $InstallDir"
}

if ($RemoveData -and (Test-Path $dataDir)) {
    Remove-Item -Recurse -Force $dataDir
    Write-Host "Removed $dataDir (token, queue and logs)"
} elseif (Test-Path $dataDir) {
    Write-Host "Kept $dataDir (token, queue, logs). Use -RemoveData to delete it."
}
Write-Host 'Remember to revoke this agent in the manager area (Dispositivi) if the PC no longer prints for StandPOS.'
