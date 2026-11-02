<#
    Owner login with email and password: CSRF, failures without lockout, "me", logout.
    Leaves the logged-in owner client in $Ctx.OwnerClient for the next scripts.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$S = @{ Client = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'owner-auth' }

Test-Step 'CSRF endpoint sets the XSRF-TOKEN cookie and names the header' {
    $csrf = Initialize-Csrf -Client $S.Client
    Assert-Equal 'X-XSRF-TOKEN' $csrf.headerName 'CSRF header name'
    Assert-NotNull $csrf.token 'CSRF token'
}

Test-Step 'Anonymous /auth/me answers 401 unauthenticated' {
    $r = Invoke-Api -Client $S.Client -Path '/api/v1/auth/me'
    Assert-Problem $r 401 'unauthenticated'
}

Test-Step 'Device state of a plain browser is "not enrolled"' {
    $r = Invoke-Api -Client $S.Client -Path '/api/v1/devices/current' -Expect 200
    Assert-Equal 'False' $r.Body.enrolled 'enrolled'
}

Test-Step 'Login without the CSRF header is refused (403 csrf.invalid)' {
    $r = Invoke-Login -Client $S.Client -NoCsrf `
        -Body @{ email = $Ctx.Owner.Email; password = $Ctx.Owner.Password }
    Assert-Problem $r 403 'csrf.invalid'
}

Test-Step 'Malformed login request answers 400 validation with field errors' {
    $r = Invoke-Login -Client $S.Client -Body @{ email = 'not-an-email'; password = '' }
    Assert-Problem $r 400 'validation'
    $fields = @($r.Problem.errors | ForEach-Object { $_.field })
    Assert-True ($fields -contains 'email') 'errors list the email field'
}

Test-Step 'Unknown email answers 401 auth.invalid_credentials' {
    $r = Invoke-Login -Client $S.Client `
        -Body @{ email = "e2e-nobody-$($Ctx.Stamp)@example.com"; password = 'wrong-password-123' }
    Assert-Problem $r 401 'auth.invalid_credentials'
}

Test-Step 'Wrong owner password answers 401 auth.invalid_credentials (one failure, no lockout)' {
    $r = Invoke-Login -Client $S.Client `
        -Body @{ email = $Ctx.Owner.Email; password = 'e2e-wrong-password' }
    Assert-Problem $r 401 'auth.invalid_credentials'
}

Test-Step 'Correct password logs the owner in right after the failure' {
    $r = Invoke-Login -Client $S.Client `
        -Body @{ email = $Ctx.Owner.Email; password = $Ctx.Owner.Password }
    Assert-Status $r 200
    Assert-Equal 'USER' $r.Body.kind 'principal kind'
    Assert-True (@($r.Body.memberships | Where-Object { $_.role -eq 'OWNER' }).Count -eq 1) 'owner membership'
    Assert-NotNull $r.Body.tenant.name 'tenant name'
    Assert-True ($null -eq $r.Body.device) 'no device on a password login'
    Write-Info "tenant: $($r.Body.tenant.name)"
}

Test-Step '/auth/me describes the logged-in owner' {
    $r = Invoke-Api -Client $S.Client -Path '/api/v1/auth/me' -Expect 200
    Assert-Equal 'USER' $r.Body.kind 'principal kind'
    Assert-Equal $Ctx.Owner.Email.ToLowerInvariant() ([string]$r.Body.user.email).ToLowerInvariant() 'email of me'
}

Test-Step 'State-changing request without CSRF is refused even when logged in' {
    $r = Invoke-Api -Client $S.Client -Method POST -Path '/api/v1/venues' -NoCsrf -Body @{ name = 'E2E csrf'; kind = 'EVENT' }
    Assert-Problem $r 403 'csrf.invalid'
}

Test-Step 'Logout ends the session' {
    Invoke-Api -Client $S.Client -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
    $r = Invoke-Api -Client $S.Client -Path '/api/v1/auth/me'
    Assert-Problem $r 401 'unauthenticated'
}

Test-Step 'Owner logs in again for the rest of the suite' {
    $Ctx.OwnerClient = Connect-Owner -BaseUrl $Ctx.BaseUrl -Email $Ctx.Owner.Email -Password $Ctx.Owner.Password
    $Ctx.OwnerUserId = $Ctx.OwnerClient.Me.user.id
    $Ctx.TenantName = $Ctx.OwnerClient.Me.tenant.name
}
