<#
    Usage cap and wrap-up: the month's sales counter grew by exactly the orders created (retries and refused sales
    excluded), then the run's devices are revoked and lose access.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$base = "/api/v1/venues/$($Ctx.VenueId)"

Test-Step 'Usage counter grew by the orders of this run' {
    $usage = (Invoke-Api -Client $owner -Path '/api/v1/usage' -Expect 200).Body
    Assert-Equal ([int]$Ctx.UsageBefore.sales + [int]$Ctx.SalesCreated) $usage.sales 'sales this month'
    Assert-Equal $Ctx.UsageBefore.month $usage.month 'same month'
    Assert-True ($usage.cap -gt 0) 'cap'
    Assert-Equal ([bool]($usage.sales -ge $usage.cap)) ([bool]$usage.capReached) 'capReached matches the counter'
    Write-Info "usage: $($usage.sales) of $($usage.cap) sales in $($usage.month) (warning=$($usage.warning), blocked=$($usage.blocked))"
}

Test-Step 'Only a platform admin may change the cap (403 access_denied for the owner)' {
    $tenantId = $owner.Me.tenant.id
    $r = Invoke-Api -Client $owner -Method PUT -Path "/api/v1/admin/tenants/$tenantId/sales-cap" -Body @{ salesCapOverride = 5000 }
    Assert-Problem $r 403 'access_denied'
}

Test-Step "The run's devices are revoked and lose access" {
    foreach ($d in @((Invoke-Api -Client $owner -Path "$base/devices" -Expect 200).Body)) {
        if ($null -eq $d.revokedAt -and ([string]$d.name).StartsWith('E2E ')) {
            Invoke-Api -Client $owner -Method POST -Path "$base/devices/$($d.id)/revoke" -Expect 200 | Out-Null
        }
    }
    Assert-Equal 'False' (Invoke-Api -Client $Ctx.Register -Path '/api/v1/devices/current' -Expect 200).Body.enrolled 'register no longer enrolled'
    Assert-Equal 'False' (Invoke-Api -Client $Ctx.Agent -Path '/api/v1/devices/current' -Expect 200).Body.enrolled 'agent no longer enrolled'
    $r = Invoke-Api -Client $Ctx.Register -Method POST -Path '/api/v1/auth/pin' -Body @{ userId = $Ctx.Users.Cashier.Id; pin = $Ctx.Users.Cashier.Pin }
    Assert-True (@(401, 403) -contains $r.Status) "PIN login on a revoked register refused (got $($r.Status))"
}

Test-Step 'Owner logs out' {
    Invoke-Api -Client $owner -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
}
