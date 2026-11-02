#Requires -Version 5.1
<#
.SYNOPSIS
    Shared helpers of the StandPOS end-to-end suite: an HTTP client with a cookie session and CSRF, RFC 9457
    problem details, assertions and the pass/fail record that Run-All.ps1 summarises.

.NOTES
    Windows PowerShell 5.1 compatible, no extra modules. Bodies are sent and read as UTF-8 (Invoke-WebRequest in
    5.1 decodes application/json as ISO-8859-1, which breaks accented names), so requests go through HttpWebRequest with
    the WebRequestSession's cookie container.
#>

Set-StrictMode -Version 2.0

$script:Results = New-Object System.Collections.Generic.List[object]
$script:CurrentScript = ''

# ---------------------------------------------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------------------------------------------

function Read-EnvFile {
    <# Reads KEY=value lines (comments and blank lines ignored) into a hashtable. Values are never printed. #>
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Env file not found: $Path (pass -EnvFile)"
    }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$Matches[1]] = $value
        }
    }
    return $values
}

function Get-RunStamp {
    <# A short, sortable identifier of this run, used in test-only names and document references. #>
    return (Get-Date).ToString('yyyyMMdd-HHmmss')
}

# ---------------------------------------------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------------------------------------------

function New-ApiClient {
    <#
    .SYNOPSIS
        A browser-like client: its own cookie session (session cookie, XSRF-TOKEN, SP_DEVICE), or a bearer token
        for a print agent (cookieless, no CSRF).
    #>
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [string]$Name = 'client',
        [string]$BearerToken
    )
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $base = $BaseUrl.TrimEnd('/')
    return [pscustomobject]@{
        Name        = $Name
        BaseUrl     = $base
        BaseUri     = [Uri]$base
        Session     = $session
        BearerToken = $BearerToken
    }
}

function Get-ClientCookie {
    <# A cookie of the client's session by name, or $null. Looks at secure cookies too (see Update-CookieFlags). #>
    param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][string]$Name)
    $builder = New-Object System.UriBuilder($Client.BaseUri)
    $builder.Scheme = 'https'
    $builder.Port = $Client.BaseUri.Port
    foreach ($uri in @($Client.BaseUri, $builder.Uri)) {
        foreach ($cookie in $Client.Session.Cookies.GetCookies($uri)) {
            if ($cookie.Name -eq $Name) { return $cookie }
        }
    }
    return $null
}

function Update-CookieFlags {
    <#
    The backend marks its cookies Secure (as in production). The local demo is plain http behind Caddy on
    127.0.0.1, where .NET would then never send them back: on an http base URL the Secure flag is dropped
    client-side, which is what a browser does for localhost.
    #>
    param([Parameter(Mandatory)]$Client)
    if ($Client.BaseUri.Scheme -ne 'http') { return }
    $builder = New-Object System.UriBuilder($Client.BaseUri)
    $builder.Scheme = 'https'
    $builder.Port = $Client.BaseUri.Port
    foreach ($cookie in $Client.Session.Cookies.GetCookies($builder.Uri)) {
        if ($cookie.Secure) { $cookie.Secure = $false }
    }
}

function Invoke-Api {
    <#
    .SYNOPSIS
        Sends one JSON request and returns { Status, Body, Problem, Text, Headers }. Never throws on an HTTP error
        status unless -Expect is given and the status is not among the expected ones.
    .PARAMETER NoCsrf
        Leaves out the X-XSRF-TOKEN header (to test that the backend refuses the request).
    #>
    param(
        [Parameter(Mandatory)]$Client,
        [ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method = 'GET',
        [Parameter(Mandatory)][string]$Path,
        $Body,
        [int[]]$Expect,
        [switch]$NoCsrf,
        [int]$TimeoutSec = 30
    )
    $url = $Client.BaseUrl + $Path
    $request = [System.Net.HttpWebRequest]::Create($url)
    $request.Method = $Method
    $request.Accept = 'application/json, application/problem+json'
    $request.CookieContainer = $Client.Session.Cookies
    $request.Timeout = $TimeoutSec * 1000
    $request.ReadWriteTimeout = $TimeoutSec * 1000
    $request.AllowAutoRedirect = $false
    $request.UserAgent = 'StandPOS-E2E/1.0 (WindowsPowerShell)'
    if ($Client.BearerToken) {
        $request.Headers.Add('Authorization', 'Bearer ' + $Client.BearerToken)
    }
    elseif (-not $NoCsrf -and $Method -ne 'GET') {
        $xsrf = Get-ClientCookie -Client $Client -Name 'XSRF-TOKEN'
        if ($null -ne $xsrf) { $request.Headers.Add('X-XSRF-TOKEN', $xsrf.Value) }
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $json = if ($Body -is [string]) { $Body } else { ConvertTo-Json -InputObject $Body -Depth 20 -Compress }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $request.ContentType = 'application/json; charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    }
    elseif ($Method -ne 'GET') {
        $request.ContentLength = 0
    }

    $response = $null
    try {
        $response = $request.GetResponse()
    }
    catch [System.Net.WebException] {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw "$Method $Path failed without an HTTP response: $($_.Exception.Message)"
        }
    }
    catch {
        $inner = $_.Exception.InnerException
        if ($inner -is [System.Net.WebException] -and $null -ne $inner.Response) {
            $response = $inner.Response
        }
        else {
            throw "$Method $Path failed without an HTTP response: $($_.Exception.Message)"
        }
    }

    try {
        $status = [int]$response.StatusCode
        $contentType = [string]$response.ContentType
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $headers = @{}
        foreach ($key in $response.Headers.AllKeys) { $headers[$key] = $response.Headers[$key] }
    }
    finally {
        $response.Close()
    }
    Update-CookieFlags -Client $Client

    $parsed = $null
    if ($text -and ($contentType -match 'json')) {
        $parsed = ConvertFrom-Json -InputObject $text
    }
    $problem = $null
    if ($status -ge 400 -and $null -ne $parsed -and ($parsed.PSObject.Properties.Name -contains 'status')) {
        $problem = $parsed
    }
    $result = [pscustomobject]@{
        Method  = $Method
        Path    = $Path
        Status  = $status
        Body    = $parsed
        Problem = $problem
        Text    = $text
        Headers = $headers
    }
    if ($PSBoundParameters.ContainsKey('Expect') -and ($Expect -notcontains $status)) {
        throw "$Method $Path`: expected HTTP $($Expect -join '/'), got $status$(Format-Problem $problem)"
    }
    return $result
}

function Format-Problem {
    <# " (code: x, detail: y)" for an RFC 9457 problem, or an empty string. #>
    param($Problem)
    if ($null -eq $Problem) { return '' }
    $parts = @()
    if ($Problem.PSObject.Properties.Name -contains 'code') { $parts += "code: $($Problem.code)" }
    if ($Problem.PSObject.Properties.Name -contains 'detail') { $parts += "detail: $($Problem.detail)" }
    if ($Problem.PSObject.Properties.Name -contains 'errors') {
        $parts += 'fields: ' + (($Problem.errors | ForEach-Object { "$($_.field) $($_.message)" }) -join '; ')
    }
    return ' (' + ($parts -join ', ') + ')'
}

function Initialize-Csrf {
    <# Fetches the CSRF cookie the way the SPA does before its first state-changing request. #>
    param([Parameter(Mandatory)]$Client)
    $r = Invoke-Api -Client $Client -Path '/api/v1/auth/csrf' -Expect 200
    if ($null -eq (Get-ClientCookie -Client $Client -Name 'XSRF-TOKEN')) {
        throw 'GET /api/v1/auth/csrf did not set the XSRF-TOKEN cookie'
    }
    return $r.Body
}

function Invoke-Login {
    <#
    .SYNOPSIS
        POST /api/v1/auth/login, waiting out the per-IP login rate limit (429 auth.rate_limited, 10 attempts a
        minute by default) instead of failing when the suite is run several times in a row.
    #>
    param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][hashtable]$Body, [switch]$NoCsrf, [int]$MaxWaitSec = 75)
    $waited = 0
    while ($true) {
        $r = Invoke-Api -Client $Client -Method POST -Path '/api/v1/auth/login' -Body $Body -NoCsrf:$NoCsrf
        if ($r.Status -ne 429 -or $waited -ge $MaxWaitSec) { return $r }
        if ($waited -eq 0) { Write-Info 'login rate limit reached: waiting for the next minute' }
        Start-Sleep -Seconds 15
        $waited += 15
    }
}

function Connect-Owner {
    <# A new client logged in with email and password. Returns the client; the "me" description is in .Me. #>
    param(
        [Parameter(Mandatory)][string]$BaseUrl,
        [Parameter(Mandatory)][string]$Email,
        [Parameter(Mandatory)][string]$Password,
        [string]$Name = 'owner'
    )
    $client = New-ApiClient -BaseUrl $BaseUrl -Name $Name
    Initialize-Csrf -Client $client | Out-Null
    $r = Invoke-Login -Client $client -Body @{ email = $Email; password = $Password }
    if ($r.Status -ne 200) {
        throw "Login as the owner failed: HTTP $($r.Status)$(Format-Problem $r.Problem)"
    }
    $client | Add-Member -NotePropertyName Me -NotePropertyValue $r.Body -Force
    return $client
}

# ---------------------------------------------------------------------------------------------------------------
# Small helpers on API data
# ---------------------------------------------------------------------------------------------------------------

function Find-ByName {
    <# The first item whose .name (or another property) equals $Name, case-insensitively, or $null. #>
    param($Items, [Parameter(Mandatory)][string]$Name, [string]$Property = 'name')
    foreach ($item in @($Items)) {
        if ($null -ne $item -and [string]::Equals([string]$item.$Property, $Name, [StringComparison]::OrdinalIgnoreCase)) {
            return $item
        }
    }
    return $null
}

function New-ClientUuid {
    <# A random UUID as the register generates for idempotent orders. #>
    return [guid]::NewGuid().ToString()
}

function Get-Today {
    return (Get-Date).ToString('yyyy-MM-dd')
}

function Get-BusinessDate {
    <#
    .SYNOPSIS
        Today's business date of a venue: the local date in its time zone, minus one day before the day change.
        Returns $null for a time zone this PC cannot map (only the IANA zones in the table below are known).
    #>
    param([Parameter(Mandatory)][string]$TimeZone, [Parameter(Mandatory)][string]$DayChangeTime)
    $windowsIds = @{
        'Europe/Rome' = 'W. Europe Standard Time'; 'Europe/Vatican' = 'W. Europe Standard Time'
        'Europe/San_Marino' = 'W. Europe Standard Time'; 'Europe/Berlin' = 'W. Europe Standard Time'
        'Europe/Zurich' = 'W. Europe Standard Time'; 'Europe/Paris' = 'Romance Standard Time'
        'Europe/London' = 'GMT Standard Time'; 'UTC' = 'UTC'
    }
    if (-not $windowsIds.ContainsKey($TimeZone)) { return $null }
    $zone = [TimeZoneInfo]::FindSystemTimeZoneById($windowsIds[$TimeZone])
    $local = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $zone)
    $change = [TimeSpan]::Parse($DayChangeTime)
    $day = $local.Date
    if ($local.TimeOfDay -lt $change) { $day = $day.AddDays(-1) }
    return $day.ToString('yyyy-MM-dd')
}

# ---------------------------------------------------------------------------------------------------------------
# Money and receipts
# ---------------------------------------------------------------------------------------------------------------

# Kept as char codes: the scripts stay ASCII so that PowerShell 5.1 reads them without a byte order mark.
$script:Euro = [string][char]0x20AC
$script:NonFiscalNotice = 'Scontrino di cortesia ' + [char]0x2013 + ' documento non fiscale'

# Notes and coins the register's cash dialog offers, in cents.
$script:Denominations = @(50, 100, 200, 500, 1000, 2000, 5000)

function Format-Cents {
    <# Cents as the receipt prints them (backend Money.format): 1234567 -> "12.345,67". #>
    param([Parameter(Mandatory)][long]$Cents)
    $sign = if ($Cents -lt 0) { '-' } else { '' }
    $abs = [Math]::Abs($Cents)
    $euros = [string][long](($abs - ($abs % 100)) / 100)
    $grouped = ''
    for ($i = 0; $i -lt $euros.Length; $i++) {
        if ($i -gt 0 -and (($euros.Length - $i) % 3) -eq 0) { $grouped += '.' }
        $grouped += $euros[$i]
    }
    return '{0}{1},{2:D2}' -f $sign, $grouped, [int]($abs % 100)
}

function Format-Euro {
    <# As on the TOTALE line: "<euro sign> 9,00". #>
    param([Parameter(Mandatory)][long]$Cents)
    return $script:Euro + ' ' + (Format-Cents $Cents)
}

function Get-TenderCents {
    <# The amount handed over as notes and coins (cents), each of them one the cash dialog offers. #>
    param([Parameter(Mandatory)][long[]]$Pieces)
    $sum = 0
    foreach ($p in $Pieces) {
        if ($script:Denominations -notcontains $p) { throw "Not a denomination of the cash dialog: $p cents" }
        $sum += $p
    }
    return [long]$sum
}

function Assert-ReceiptPreview {
    <#
    .SYNOPSIS
        The server-built receipt preview matches the order: the fixed non-fiscal line, the order number, every line
        with its amount, the total, and cash tendered and change (or card).
    #>
    param([Parameter(Mandatory)]$Preview, [Parameter(Mandatory)]$Order)
    Assert-True ($Preview.columns -ge 32) "preview width $($Preview.columns) columns"
    $texts = @($Preview.lines | ForEach-Object { [string]$_.text })
    $trimmed = @($texts | ForEach-Object { $_.Trim() })
    $wide = @($Preview.lines | Where-Object { $_.text.Length -gt $Preview.columns })
    Assert-True ($wide.Count -eq 0) 'no line wider than the paper'
    Assert-True ($trimmed -contains $script:NonFiscalNotice) 'fixed non-fiscal line'
    Assert-True ($trimmed -contains "N. $($Order.dailyNumber)") "order number N. $($Order.dailyNumber)"

    function Find-Row([string]$Left, [string]$Right) {
        return @($texts | Where-Object { $_.StartsWith($Left) -and $_.TrimEnd().EndsWith($Right) }).Count -gt 0
    }
    foreach ($line in $Order.lines) {
        $name = if ($line.name.Length -gt 20) { $line.name.Substring(0, 20) } else { $line.name }
        Assert-True (Find-Row $name (Format-Cents $line.lineTotalCents)) "row of '$($line.name)' with $(Format-Cents $line.lineTotalCents)"
    }
    Assert-True (Find-Row 'TOTALE' (Format-Euro $Order.totalCents)) "TOTALE $(Format-Cents $Order.totalCents)"
    if ($Order.payment.method -eq 'CASH') {
        $tendered = if ($null -ne $Order.payment.tenderedCents) { [long]$Order.payment.tenderedCents } else { [long]$Order.totalCents }
        $change = if ($null -ne $Order.payment.changeCents) { [long]$Order.payment.changeCents } else { 0 }
        Assert-True (Find-Row 'Contanti' (Format-Euro $tendered)) "Contanti $(Format-Cents $tendered)"
        Assert-True (Find-Row 'Resto' (Format-Euro $change)) "Resto $(Format-Cents $change)"
    }
    else {
        Assert-True (Find-Row 'Carta' (Format-Euro $Order.totalCents)) 'Carta row'
    }
}

# ---------------------------------------------------------------------------------------------------------------
# Assertions and results
# ---------------------------------------------------------------------------------------------------------------

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function Assert-Equal {
    param($Expected, $Actual, [Parameter(Mandatory)][string]$Message)
    if (-not ("$Expected" -ceq "$Actual")) {
        throw "Assertion failed: $Message (expected '$Expected', got '$Actual')"
    }
}

function Assert-NotNull {
    param($Value, [Parameter(Mandatory)][string]$Message)
    if ($null -eq $Value -or ($Value -is [string] -and $Value -eq '')) { throw "Assertion failed: $Message (value is empty)" }
}

function Assert-Status {
    param([Parameter(Mandatory)]$Response, [Parameter(Mandatory)][int[]]$Status)
    if ($Status -notcontains $Response.Status) {
        throw "Assertion failed: $($Response.Method) $($Response.Path) expected HTTP $($Status -join '/'), got $($Response.Status)$(Format-Problem $Response.Problem)"
    }
}

function Assert-Problem {
    <# The response is an RFC 9457 problem with this status and stable code. #>
    param([Parameter(Mandatory)]$Response, [Parameter(Mandatory)][int]$Status, [Parameter(Mandatory)][string]$Code)
    Assert-Status -Response $Response -Status $Status
    if ($null -eq $Response.Problem) {
        throw "Assertion failed: $($Response.Method) $($Response.Path) answered $Status without a problem body"
    }
    $actual = if ($Response.Problem.PSObject.Properties.Name -contains 'code') { $Response.Problem.code } else { '<none>' }
    if ($actual -ne $Code) {
        throw "Assertion failed: $($Response.Method) $($Response.Path) expected problem code '$Code', got '$actual'$(Format-Problem $Response.Problem)"
    }
    # 'type' may be absent: RFC 9457 reads it as "about:blank" (Spring's own errors, e.g. a malformed parameter).
    foreach ($field in 'title', 'status') {
        if ($Response.Problem.PSObject.Properties.Name -notcontains $field) {
            throw "Assertion failed: problem detail of $($Response.Path) has no '$field'"
        }
    }
}

function Start-TestScript {
    <# Called by Run-All.ps1 before each script, to group the steps in the summary. #>
    param([Parameter(Mandatory)][string]$Name)
    $script:CurrentScript = $Name
    Write-Host ''
    Write-Host "== $Name" -ForegroundColor Cyan
}

function Test-Step {
    <#
    .SYNOPSIS
        Runs one named check, records PASS or FAIL and rethrows a failure so the script stops there (later steps
        depend on earlier ones). Keep state shared between steps in a hashtable such as $Ctx: the block runs in
        its own scope.
    #>
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Body)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Body
        $watch.Stop()
        $script:Results.Add([pscustomobject]@{ Script = $script:CurrentScript; Step = $Name; Outcome = 'PASS'; Ms = $watch.ElapsedMilliseconds; Error = $null })
        Write-Host ("  [PASS] {0}" -f $Name) -ForegroundColor Green
    }
    catch {
        $watch.Stop()
        $message = $_.Exception.Message
        $script:Results.Add([pscustomobject]@{ Script = $script:CurrentScript; Step = $Name; Outcome = 'FAIL'; Ms = $watch.ElapsedMilliseconds; Error = $message })
        Write-Host ("  [FAIL] {0}" -f $Name) -ForegroundColor Red
        Write-Host ("         {0}" -f $message) -ForegroundColor Red
        throw
    }
}

function Write-Info {
    <# A line of information under the current script (never pass secrets). #>
    param([Parameter(Mandatory)][string]$Message)
    Write-Host ("         {0}" -f $Message) -ForegroundColor DarkGray
}

function Get-TestResults {
    return $script:Results.ToArray()
}

Export-ModuleMember -Function Read-EnvFile, Get-RunStamp, New-ApiClient, Get-ClientCookie, Invoke-Api, Format-Problem,
    Initialize-Csrf, Invoke-Login, Connect-Owner, Find-ByName, New-ClientUuid, Get-Today, Get-BusinessDate,
    Format-Cents, Format-Euro, Get-TenderCents, Assert-ReceiptPreview, Assert-True, Assert-Equal, Assert-NotNull,
    Assert-Status, Assert-Problem, Start-TestScript, Test-Step, Write-Info, Get-TestResults
