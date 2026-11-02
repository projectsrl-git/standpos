#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the StandPOS end-to-end suite against the local demo or the cloud demo and prints a pass/fail summary.

.DESCRIPTION
    Every script in .\scripts runs in name order and shares one context (base URL, owner session, venue and the
    ids created on the way). A failing step stops its script; the scripts after it are skipped, because they
    build on its data. The exit code is 0 only when every step passed.

    The suite writes to the target: it creates or reuses a demo catalogue, suppliers and stock in the venue, test
    users and devices prefixed "E2E", and a handful of sales. Re-running it is safe.

.PARAMETER Target
    local: http://127.0.0.1:8088 (Caddy of the local demo). cloud: the URL in -CloudUrlFile.

.PARAMETER BaseUrl
    Overrides the URL chosen by -Target.

.PARAMETER EnvFile
    KEY=value file with STANDPOS_BOOTSTRAP_OWNER_EMAIL, _PASSWORD and _PIN (and optionally _VENUE_NAME).

.PARAMETER VenueName
    Venue to test in. Defaults to STANDPOS_BOOTSTRAP_VENUE_NAME from the env file, else the first venue.

.PARAMETER Only
    Runs only the scripts whose name matches one of these wildcards (e.g. '0[1-4]*'). Later scripts need the
    data of earlier ones, so pick a prefix of the list.

.EXAMPLE
    .\tests\e2e\Run-All.ps1 -Target local

.EXAMPLE
    .\tests\e2e\Run-All.ps1 -Target cloud -EnvFile D:\SVILUPPO\standpos-demo\deploy.env
#>
[CmdletBinding()]
param(
    [ValidateSet('local', 'cloud')][string]$Target = 'local',
    [string]$BaseUrl,
    [string]$EnvFile = 'D:\SVILUPPO\standpos-demo\deploy.env',
    [string]$CloudUrlFile = 'D:\SVILUPPO\standpos-demo\cloud-url.txt',
    [string]$VenueName,
    [string[]]$Only
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Import-Module (Join-Path $PSScriptRoot 'lib\StandposE2E.psm1') -Force

function Resolve-BaseUrl {
    if ($BaseUrl) { return $BaseUrl.TrimEnd('/') }
    if ($Target -eq 'local') { return 'http://127.0.0.1:8088' }
    if (-not (Test-Path -LiteralPath $CloudUrlFile)) {
        throw "The cloud demo URL file does not exist yet: $CloudUrlFile. Deploy the cloud demo first, or pass -BaseUrl https://..."
    }
    $url = (Get-Content -LiteralPath $CloudUrlFile -Encoding UTF8 | Where-Object { $_.Trim() } | Select-Object -First 1)
    if (-not $url -or $url.Trim() -notmatch '^https?://') {
        throw "No http(s) URL on the first line of $CloudUrlFile"
    }
    return $url.Trim().TrimEnd('/')
}

$started = Get-Date
try {
    $url = Resolve-BaseUrl
    $envValues = Read-EnvFile -Path $EnvFile
    foreach ($key in 'STANDPOS_BOOTSTRAP_OWNER_EMAIL', 'STANDPOS_BOOTSTRAP_OWNER_PASSWORD', 'STANDPOS_BOOTSTRAP_OWNER_PIN') {
        if (-not $envValues.ContainsKey($key) -or -not $envValues[$key]) { throw "$key is missing in $EnvFile" }
    }
}
catch {
    Write-Host "E2E setup error: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

if (-not $VenueName -and $envValues.ContainsKey('STANDPOS_BOOTSTRAP_VENUE_NAME')) {
    $VenueName = $envValues['STANDPOS_BOOTSTRAP_VENUE_NAME']
}

Write-Host "StandPOS E2E suite"
Write-Host "  target : $Target ($url)"
Write-Host "  env    : $EnvFile"

try {
    $health = Invoke-RestMethod -Uri "$url/actuator/health" -TimeoutSec 20 -UseBasicParsing
    if ($health.status -ne 'UP') { throw "health is $($health.status)" }
}
catch {
    Write-Host "E2E setup error: $url/actuator/health is not UP: $($_.Exception.Message)" -ForegroundColor Red
    exit 2
}

# Shared state of the run. Secrets stay in here and are never printed.
$ctx = @{
    BaseUrl   = $url
    Target    = $Target
    Stamp     = Get-RunStamp
    Owner     = @{
        Email    = $envValues['STANDPOS_BOOTSTRAP_OWNER_EMAIL']
        Password = $envValues['STANDPOS_BOOTSTRAP_OWNER_PASSWORD']
        Pin      = $envValues['STANDPOS_BOOTSTRAP_OWNER_PIN']
    }
    VenueName = $VenueName
    Data      = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'data\demo-data.json') -Raw -Encoding UTF8 | ConvertFrom-Json
}

$scripts = @(Get-ChildItem -Path (Join-Path $PSScriptRoot 'scripts') -Filter '*.ps1' | Sort-Object Name)
if ($Only) {
    $scripts = @($scripts | Where-Object { $n = $_.Name; @($Only | Where-Object { $n -like $_ }).Count -gt 0 })
}
if ($scripts.Count -eq 0) {
    Write-Host 'E2E setup error: no test scripts selected' -ForegroundColor Red
    exit 2
}

$scriptOutcome = [ordered]@{}
$failed = $false
foreach ($file in $scripts) {
    if ($failed) {
        $scriptOutcome[$file.BaseName] = 'SKIPPED'
        continue
    }
    Start-TestScript -Name $file.BaseName
    try {
        & $file.FullName -Ctx $ctx
        $scriptOutcome[$file.BaseName] = 'PASS'
    }
    catch {
        $scriptOutcome[$file.BaseName] = 'FAIL'
        $failed = $true
        $last = @(Get-TestResults | Where-Object { $_.Script -eq $file.BaseName })
        if ($last.Count -eq 0 -or $last[-1].Outcome -ne 'FAIL') {
            # Failed outside a Test-Step: show the error too.
            Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
        }
    }
}

$results = @(Get-TestResults)
$passed = @($results | Where-Object { $_.Outcome -eq 'PASS' }).Count
$failedSteps = @($results | Where-Object { $_.Outcome -eq 'FAIL' })
$elapsed = [int]((Get-Date) - $started).TotalSeconds

Write-Host ''
Write-Host '== Summary' -ForegroundColor Cyan
foreach ($name in $scriptOutcome.Keys) {
    $outcome = $scriptOutcome[$name]
    $steps = @($results | Where-Object { $_.Script -eq $name }).Count
    $colour = switch ($outcome) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("  {0,-8} {1,-28} {2,3} steps" -f $outcome, $name, $steps) -ForegroundColor $colour
}
foreach ($f in $failedSteps) {
    Write-Host "  failed: $($f.Script) / $($f.Step): $($f.Error)" -ForegroundColor Red
}
$overall = if ($failed) { 'FAILED' } else { 'PASSED' }
Write-Host ("  {0}: {1} steps passed, {2} failed, {3} scripts skipped, {4}s against {5}" -f $overall, $passed,
    $failedSteps.Count, @($scriptOutcome.Values | Where-Object { $_ -eq 'SKIPPED' }).Count, $elapsed, $url) `
    -ForegroundColor $(if ($failed) { 'Red' } else { 'Green' })

if ($failed) { exit 1 }
exit 0
