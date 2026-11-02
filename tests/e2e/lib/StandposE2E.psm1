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
    foreach ($field in 'type', 'title', 'status') {
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
    Initialize-Csrf, Invoke-Login, Connect-Owner, Find-ByName, New-ClientUuid, Get-Today, Assert-True, Assert-Equal, Assert-NotNull,
    Assert-Status, Assert-Problem, Start-TestScript, Test-Step, Write-Info, Get-TestResults
