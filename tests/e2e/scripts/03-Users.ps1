<#
    Users and memberships (owner only): a cashier and a manager of the venue under test with PINs, a volunteer of
    the event venue only, and a manager of the event venue who logs in with email and password (to check that a
    manager does not see another venue). All are test users named "E2E ..."; their PINs are fixed test values, reset
    on each run, and the event manager's password is a fixed test value set when the user is created.
    Sets $Ctx.Users (key -> @{ Id; Name; Pin; Email; Password }).
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$Ctx.Users = @{}
$wanted = @(
    @{ Key = 'Cashier'; Name = 'E2E Cassiere'; Pin = '2468'; Role = 'CASHIER'; VenueId = $Ctx.VenueId }
    @{ Key = 'Manager'; Name = 'E2E Responsabile'; Pin = '1357'; Role = 'MANAGER'; VenueId = $Ctx.VenueId }
    @{ Key = 'Volunteer'; Name = 'E2E Volontario festa'; Pin = '9753'; Role = 'CASHIER'; VenueId = $Ctx.EventVenueId }
    @{ Key = 'EventManager'; Name = 'E2E Responsabile festa'; Pin = '8642'; Role = 'MANAGER'; VenueId = $Ctx.EventVenueId
        Email = 'e2e-responsabile-festa@standpos.test'; Password = 'E2E-test-festa-2026' }
)

foreach ($w in $wanted) {
    Test-Step "User '$($w.Name)' is $($w.Role) with a PIN (created or reused)" {
        $users = @((Invoke-Api -Client $owner -Path '/api/v1/users' -Expect 200).Body)
        $user = Find-ByName $users $w.Name -Property 'displayName'
        if ($null -eq $user) {
            $body = @{ displayName = $w.Name; pin = $w.Pin; venueId = $w.VenueId; role = $w.Role }
            if ($w.ContainsKey('Email')) { $body.email = $w.Email; $body.password = $w.Password }
            $user = (Invoke-Api -Client $owner -Method POST -Path '/api/v1/users' -Expect 201 -Body $body).Body
            Write-Info "created $($w.Name)"
        }
        else {
            # Known PIN on every run, and no lock left over from an interrupted run.
            $user = (Invoke-Api -Client $owner -Method PUT -Path "/api/v1/users/$($user.id)" -Expect 200 -Body @{
                    displayName = $w.Name; pin = $w.Pin; clearPin = $false
                }).Body
            $user = (Invoke-Api -Client $owner -Method POST -Path "/api/v1/users/$($user.id)/unlock" -Expect 200 -Body @{}).Body
            $has = @($user.memberships | Where-Object { $_.role -eq $w.Role -and $_.venueId -eq $w.VenueId }).Count -gt 0
            if (-not $has) {
                $user = (Invoke-Api -Client $owner -Method POST -Path "/api/v1/users/$($user.id)/memberships" -Expect 201 `
                        -Body @{ venueId = $w.VenueId; role = $w.Role }).Body
            }
        }
        Assert-True ([bool]$user.hasPin) 'hasPin'
        Assert-Equal ([string]$w.ContainsKey('Email')) ([string][bool]$user.hasPassword) 'password only for the email user'
        Assert-True (@($user.memberships | Where-Object { $_.role -eq $w.Role -and $_.venueId -eq $w.VenueId }).Count -eq 1) 'membership'
        $Ctx.Users[$w.Key] = @{ Id = $user.id; Name = $w.Name; Pin = $w.Pin; Email = $w['Email']; Password = $w['Password'] }
    }
}

Test-Step 'Same role twice answers 409 user.membership_exists' {
    $r = Invoke-Api -Client $owner -Method POST -Path "/api/v1/users/$($Ctx.Users.Cashier.Id)/memberships" `
        -Body @{ venueId = $Ctx.VenueId; role = 'CASHIER' }
    Assert-Problem $r 409 'user.membership_exists'
}

Test-Step 'A venue role without a venue answers 400 user.invalid_role' {
    $r = Invoke-Api -Client $owner -Method POST -Path '/api/v1/users' -Body @{ displayName = 'E2E invalid'; pin = '1111'; role = 'CASHIER' }
    Assert-Problem $r 400 'user.invalid_role'
}

Test-Step 'PIN that is not 4-6 digits answers 400 validation' {
    $r = Invoke-Api -Client $owner -Method POST -Path '/api/v1/users' -Body @{
        displayName = 'E2E invalid'; pin = '12ab'; role = 'CASHIER'; venueId = $Ctx.VenueId
    }
    Assert-Problem $r 400 'validation'
}

Test-Step "The venue's people list shows cashier and manager with their roles" {
    $people = @((Invoke-Api -Client $owner -Path "/api/v1/venues/$($Ctx.VenueId)/users" -Expect 200).Body)
    $cashier = Find-ByName $people 'E2E Cassiere' -Property 'displayName'
    $manager = Find-ByName $people 'E2E Responsabile' -Property 'displayName'
    Assert-NotNull $cashier 'cashier in the venue list'
    Assert-NotNull $manager 'manager in the venue list'
    Assert-True (@($cashier.roles) -contains 'CASHIER') 'cashier role'
    Assert-True (@($manager.roles) -contains 'MANAGER') 'manager role'
    Assert-True ($null -eq (Find-ByName $people 'E2E Volontario festa' -Property 'displayName')) 'event volunteer not listed here'
}
