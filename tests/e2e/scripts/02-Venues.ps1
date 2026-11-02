<#
    Venues: picks the venue under test and creates (or reuses) an event venue marked "E2E".
    Sets $Ctx.VenueId, $Ctx.Venue and $Ctx.EventVenueId.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$S = @{ EventName = "E2E Festa dell'oratorio" }

Test-Step 'Owner lists the venues and picks the venue under test' {
    $venues = @((Invoke-Api -Client $owner -Path '/api/v1/venues' -Expect 200).Body)
    Assert-True ($venues.Count -ge 1) 'at least one venue'
    $venue = if ($Ctx.VenueName) { Find-ByName $venues $Ctx.VenueName } else { $venues[0] }
    Assert-NotNull $venue "venue '$($Ctx.VenueName)'"
    Assert-True ([bool]$venue.active) 'the venue under test is active'
    $Ctx.VenueId = $venue.id
    $Ctx.Venue = $venue
    Write-Info "venue: $($venue.name) ($($venue.kind), $($venue.timeZone), day change $($venue.dayChangeTime))"
}

Test-Step 'Event venue exists (created or reused by name)' {
    $venues = @((Invoke-Api -Client $owner -Path '/api/v1/venues' -Expect 200).Body)
    $event = Find-ByName $venues $S.EventName
    if ($null -eq $event) {
        $event = (Invoke-Api -Client $owner -Method POST -Path '/api/v1/venues' -Expect 201 -Body @{
                name = $S.EventName; kind = 'EVENT'; timeZone = 'Europe/Rome'; dayChangeTime = '05:00'
                receiptHeader = "Festa dell'oratorio"; receiptFooter = 'Grazie e buona festa!'
            }).Body
        Write-Info 'created the event venue'
    }
    Assert-Equal 'EVENT' $event.kind 'kind'
    Assert-Equal 'Europe/Rome' $event.timeZone 'time zone'
    $Ctx.EventVenueId = $event.id
}

Test-Step 'Owner updates the event venue' {
    $footer = "Grazie e buona festa! (E2E $($Ctx.Stamp))"
    $r = Invoke-Api -Client $owner -Method PUT -Path "/api/v1/venues/$($Ctx.EventVenueId)" -Expect 200 -Body @{
        name = $S.EventName; kind = 'EVENT'; timeZone = 'Europe/Rome'; dayChangeTime = '05:00'
        receiptHeader = "Festa dell'oratorio"; receiptFooter = $footer; active = $true; allowNegativeStock = $true
    }
    Assert-Equal $footer $r.Body.receiptFooter 'receipt footer'
    Assert-Equal '05:00:00' $r.Body.dayChangeTime 'day change time'
    Assert-Equal 'True' $r.Body.allowNegativeStock 'negative stock allowed at the event'
}

Test-Step 'Unknown time zone answers 400 venue.invalid_time_zone' {
    $r = Invoke-Api -Client $owner -Method PUT -Path "/api/v1/venues/$($Ctx.EventVenueId)" -Body @{
        name = $S.EventName; kind = 'EVENT'; timeZone = 'Europe/Atlantide'
    }
    Assert-Problem $r 400 'venue.invalid_time_zone'
}

Test-Step 'Venue without a name answers 400 validation' {
    $r = Invoke-Api -Client $owner -Method POST -Path '/api/v1/venues' -Body @{ name = ''; kind = 'EVENT' }
    Assert-Problem $r 400 'validation'
}

Test-Step 'Unknown venue answers 404 not_found' {
    $r = Invoke-Api -Client $owner -Method PUT -Path "/api/v1/venues/$([guid]::NewGuid())" -Body @{ name = 'E2E ghost'; kind = 'EVENT' }
    Assert-Problem $r 404 'not_found'
}
