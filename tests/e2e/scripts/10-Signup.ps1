<#
    Self-service sign-up and the setup wizard (docs/adr/0015-self-service-sign-up.md): a new organisation named
    "E2E Associazione <run>" with an invented .test email signs up and is logged in, sees nothing of the demo
    tenant, goes through the wizard's API calls (venues, starter catalogue, enrolment codes, people with PINs),
    enrols a register and sells. Also: CSRF, missing declarations, and a second sign-up with the same email, which
    must answer exactly like a new one.

    Every run creates one new tenant (there is no API to delete one). Sign-up is limited to 5 attempts per client
    address per hour and each run uses 2: when the server answers 429 signup.rate_limited, or sign-up is switched
    off (404), the steps are skipped with the reason instead of failing.
    Self-contained: it needs only $Ctx.BaseUrl, $Ctx.Stamp, $Ctx.Owner and $Ctx.Data.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$S = @{
    OrgName  = "E2E Associazione $($Ctx.Stamp)"
    Email    = "e2e-associazione-$($Ctx.Stamp)@standpos.test"
    Password = 'E2E-signup-test-2026'
    Skip     = $null
}

function New-SignupBody([string]$OrgName, [bool]$Declarations = $true) {
    return @{
        organisationName = $OrgName; ownerName = 'E2E Presidente'; email = $S.Email; password = $S.Password
        nonProfit = $Declarations; nonFiscalAcknowledged = $Declarations
    }
}

Test-Step 'Sign-up availability is public and names the declarations version' {
    $anon = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'signup-probe'
    $r = Invoke-Api -Client $anon -Path '/api/v1/auth/signup'
    if ($r.Status -eq 404) {
        $S.Skip = 'sign-up is switched off on this server (GET /api/v1/auth/signup answers 404, STANDPOS_SIGNUP_ENABLED=false)'
        return
    }
    Assert-Status $r 200
    Assert-Equal 'True' $r.Body.enabled 'enabled'
    Assert-NotNull $r.Body.declarationsVersion 'declarations version'
    Write-Info "declarations version $($r.Body.declarationsVersion)"
}
if ($S.Skip) { Skip-Step 'Self-service sign-up and setup wizard' $S.Skip; return }

Test-Step 'Reference data of the demo tenant (to prove the new tenant cannot see it)' {
    $demo = Connect-Owner -BaseUrl $Ctx.BaseUrl -Email $Ctx.Owner.Email -Password $Ctx.Owner.Password -Name 'demo-owner'
    $S.DemoTenantId = $demo.Me.tenant.id
    $venues = @((Invoke-Api -Client $demo -Path '/api/v1/venues' -Expect 200).Body)
    $S.DemoVenueId = $venues[0].id
    $S.DemoVenueNames = @($venues | ForEach-Object { $_.name })
    $overview = (Invoke-Api -Client $demo -Path "/api/v1/venues/$($S.DemoVenueId)/day-overview" -Expect 200).Body
    $S.DemoOrderId = if (@($overview.orders).Count -gt 0) { $overview.orders[0].id } else { $null }
    Invoke-Api -Client $demo -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
}

Test-Step 'Sign-up without the CSRF header is refused (403 csrf.invalid)' {
    $c = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'signup-nocsrf'
    Initialize-Csrf -Client $c | Out-Null
    $r = Invoke-Api -Client $c -Method POST -Path '/api/v1/auth/signup' -NoCsrf -Body (New-SignupBody $S.OrgName)
    Assert-Problem $r 403 'csrf.invalid'
    Assert-Problem (Invoke-Api -Client $c -Path '/api/v1/auth/me') 401 'unauthenticated'
}

Test-Step 'Sign-up without the mandatory declarations answers 400 validation and creates nothing' {
    $c = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'signup-nodecl'
    Initialize-Csrf -Client $c | Out-Null
    $body = New-SignupBody $S.OrgName -Declarations $false
    $r = Invoke-Api -Client $c -Method POST -Path '/api/v1/auth/signup' -Body $body
    Assert-Problem $r 400 'validation'
    $fields = @($r.Problem.errors | ForEach-Object { $_.field })
    Assert-True ($fields -contains 'nonProfit') 'nonProfit listed'
    Assert-True ($fields -contains 'nonFiscalAcknowledged') 'nonFiscalAcknowledged listed'
    $short = New-SignupBody $S.OrgName
    $short.password = 'corta'
    Assert-Problem (Invoke-Api -Client $c -Method POST -Path '/api/v1/auth/signup' -Body $short) 400 'validation'
    Assert-Problem (Invoke-Api -Client $c -Path '/api/v1/auth/me') 401 'unauthenticated'
}

Test-Step 'A new organisation signs up and is logged in as its owner' {
    $c = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'new-owner'
    Initialize-Csrf -Client $c | Out-Null
    $r = Invoke-Api -Client $c -Method POST -Path '/api/v1/auth/signup' -Body (New-SignupBody $S.OrgName)
    if ($r.Status -eq 429) {
        $S.Skip = "sign-up hourly limit reached on this address (429 $($r.Problem.code)); run the suite again within the next hour"
        return
    }
    Assert-Status $r 202
    Assert-Equal 'accepted' $r.Body.status 'status'
    $S.AcceptedText = $r.Text
    $me = (Invoke-Api -Client $c -Path '/api/v1/auth/me' -Expect 200).Body
    Assert-Equal 'USER' $me.kind 'kind'
    Assert-Equal $S.OrgName $me.tenant.name 'tenant name'
    Assert-Equal $S.Email $me.user.email 'owner email'
    Assert-True (@($me.memberships | Where-Object { $_.role -eq 'OWNER' -and $null -eq $_.venueId }).Count -eq 1) 'OWNER membership'
    Assert-True ($me.tenant.id -ne $S.DemoTenantId) 'a new tenant, not the demo one'
    $S.Owner = $c
    $S.OwnerUserId = $me.user.id
    Write-Info "new tenant: $($me.tenant.name)"
}
if ($S.Skip) { Skip-Step 'Setup wizard, register and duplicate sign-up of the new organisation' $S.Skip; return }

Test-Step 'The new tenant starts empty and sees nothing of the demo oratorio' {
    $o = $S.Owner
    Assert-Equal 0 @((Invoke-Api -Client $o -Path '/api/v1/venues' -Expect 200).Body).Count 'no venue yet'
    $users = @((Invoke-Api -Client $o -Path '/api/v1/users' -Expect 200).Body)
    Assert-Equal 1 $users.Count 'only the owner'
    Assert-Equal $S.OwnerUserId $users[0].id 'the owner'
    Assert-Equal 0 @((Invoke-Api -Client $o -Path '/api/v1/suppliers' -Expect 200).Body).Count 'no suppliers'
    Assert-Equal 0 (Invoke-Api -Client $o -Path '/api/v1/usage' -Expect 200).Body.sales 'no sales this month'
    # The demo venue's id from the new tenant: refused, or (RLS) empty, never the demo data.
    foreach ($list in 'products', 'devices') {
        $r = Invoke-Api -Client $o -Path "/api/v1/venues/$($S.DemoVenueId)/$list"
        Assert-True (@(403, 404) -contains $r.Status -or ($r.Status -eq 200 -and @($r.Body).Count -eq 0)) "no demo $list (HTTP $($r.Status))"
    }
    $r = Invoke-Api -Client $o -Path "/api/v1/venues/$($S.DemoVenueId)/day-overview"
    Assert-True (@(403, 404) -contains $r.Status -or ($r.Status -eq 200 -and @($r.Body.orders).Count -eq 0)) "no demo orders (HTTP $($r.Status))"
    Write-Info "demo venue id from the new tenant: day overview answers HTTP $($r.Status)"
    if ($S.DemoOrderId) {
        $r = Invoke-Api -Client $o -Path "/api/v1/venues/$($S.DemoVenueId)/orders/$($S.DemoOrderId)/receipt-preview"
        Assert-True (@(403, 404) -contains $r.Status) "demo receipt not readable (HTTP $($r.Status))"
    }
}

Test-Step 'Wizard, venues: a permanent bar and a summer festival' {
    $o = $S.Owner
    $S.Bar = (Invoke-Api -Client $o -Method POST -Path '/api/v1/venues' -Expect 201 -Body @{
            name = 'E2E Bar dell''associazione'; kind = 'PERMANENT'; timeZone = 'Europe/Rome'; dayChangeTime = '04:00'
            receiptHeader = $null; receiptFooter = $null; active = $true; allowNegativeStock = $false
        }).Body
    $S.Festival = (Invoke-Api -Client $o -Method POST -Path '/api/v1/venues' -Expect 201 -Body @{
            name = "E2E Festa d'estate"; kind = 'EVENT'; timeZone = 'Europe/Rome'; dayChangeTime = '04:00'
            receiptHeader = $null; receiptFooter = $null; active = $true; allowNegativeStock = $false
        }).Body
    $venues = @((Invoke-Api -Client $o -Path '/api/v1/venues' -Expect 200).Body)
    Assert-Equal 2 $venues.Count 'two venues'
    foreach ($name in $S.DemoVenueNames) {
        Assert-True ($null -eq (Find-ByName $venues $name)) "demo venue '$name' not visible"
    }
}

Test-Step 'Wizard, starter catalogue: routes, categories and products in integer cents, no stock tracking' {
    $o = $S.Owner
    $base = "/api/v1/venues/$($S.Bar.id)"
    $starter = $Ctx.Data.starterCatalog
    $routes = @{}
    foreach ($route in $starter.routes) {
        $routes[$route.name] = (Invoke-Api -Client $o -Method POST -Path "$base/routes" -Expect 201 -Body @{ name = $route.name; sort = $route.sort }).Body.id
    }
    $categorySort = 0
    $expected = 0
    foreach ($category in $starter.categories) {
        $created = (Invoke-Api -Client $o -Method POST -Path "$base/categories" -Expect 201 -Body @{
                name = $category.name; colour = $category.colour; sort = $categorySort; defaultRouteId = $routes[$category.route]
            }).Body
        $categorySort++
        $productSort = 0
        foreach ($product in $category.products) {
            Invoke-Api -Client $o -Method POST -Path "$base/products" -Expect 201 -Body @{
                categoryId = $created.id; name = $product.name; priceCents = [long]$product.priceCents; routeId = $null
                active = $true; trackStock = $false; lowThreshold = $null; soldOut = $false; sort = $productSort
            } | Out-Null
            $productSort++
            $expected++
        }
    }
    $products = @((Invoke-Api -Client $o -Path "$base/products" -Expect 200).Body)
    Assert-Equal $expected $products.Count 'starter products'
    Assert-True (@($products | Where-Object { $_.trackStock }).Count -eq 0) 'no stock tracking'
    $S.Products = @{}
    foreach ($p in $products) { $S.Products[$p.name] = $p }
}

Test-Step 'Wizard, registers: enrolment codes for both venues and a print agent code' {
    $o = $S.Owner
    $S.BarCode = (Invoke-Api -Client $o -Method POST -Path "/api/v1/venues/$($S.Bar.id)/enrolment-codes" -Expect 201 -Body @{ kind = 'REGISTER' }).Body
    $festival = (Invoke-Api -Client $o -Method POST -Path "/api/v1/venues/$($S.Festival.id)/enrolment-codes" -Expect 201 -Body @{ kind = 'REGISTER' }).Body
    $agent = (Invoke-Api -Client $o -Method POST -Path "/api/v1/venues/$($S.Bar.id)/enrolment-codes" -Expect 201 -Body @{ kind = 'PRINT_AGENT' }).Body
    foreach ($code in $S.BarCode, $festival, $agent) {
        Assert-NotNull $code.code 'code'
        Assert-NotNull $code.expiresAt 'expiry'
    }
    Assert-Equal 'PRINT_AGENT' $agent.kind 'agent code kind'
}

Test-Step 'Wizard, people: a cashier of the bar and a manager of the festival, with PINs' {
    $o = $S.Owner
    $S.Cashier = (Invoke-Api -Client $o -Method POST -Path '/api/v1/users' -Expect 201 -Body @{
            displayName = 'E2E Cassiere associazione'; email = $null; password = $null; pin = '4826'
            venueId = $S.Bar.id; role = 'CASHIER'
        }).Body
    $S.FestivalManager = (Invoke-Api -Client $o -Method POST -Path '/api/v1/users' -Expect 201 -Body @{
            displayName = "E2E Responsabile festa d'estate"; email = $null; password = $null; pin = '6284'
            venueId = $S.Festival.id; role = 'MANAGER'
        }).Body
    Assert-True ([bool]$S.Cashier.hasPin) 'cashier PIN'
    Assert-True ([bool]$S.FestivalManager.hasPin) 'manager PIN'
}

Test-Step 'Wizard summary, as the wizard reads it back: catalogue and people per venue' {
    $o = $S.Owner
    $users = @((Invoke-Api -Client $o -Path '/api/v1/users' -Expect 200).Body)
    Assert-Equal 3 $users.Count 'owner, cashier, manager'
    $barStaff = @($users | Where-Object { @($_.memberships | Where-Object { $_.venueId -eq $S.Bar.id }).Count -gt 0 })
    $festivalStaff = @($users | Where-Object { @($_.memberships | Where-Object { $_.venueId -eq $S.Festival.id }).Count -gt 0 })
    Assert-Equal 'E2E Cassiere associazione' ($barStaff | ForEach-Object { $_.displayName }) 'staff of the bar'
    Assert-Equal "E2E Responsabile festa d'estate" ($festivalStaff | ForEach-Object { $_.displayName }) 'staff of the festival'
    Assert-Equal 3 @((Invoke-Api -Client $o -Path "/api/v1/venues/$($S.Bar.id)/categories" -Expect 200).Body).Count 'bar categories'
    Assert-Equal 0 @((Invoke-Api -Client $o -Path "/api/v1/venues/$($S.Festival.id)/products" -Expect 200).Body).Count 'festival has no catalogue yet'
}

Test-Step "The new tenant's register: enrolment, PIN login, shift, a sale paid 5 + 2 EUR, receipt preview" {
    $register = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'new-register'
    Initialize-Csrf -Client $register | Out-Null
    $device = (Invoke-Api -Client $register -Method POST -Path '/api/v1/devices/enrol' -Expect 201 -Body @{ code = $S.BarCode.code; name = 'E2E Cassa associazione' }).Body.device
    Assert-Equal $S.Bar.id $device.venueId 'register of the new bar'
    $cashiers = @((Invoke-Api -Client $register -Path '/api/v1/register/cashiers' -Expect 200).Body)
    Assert-Equal 'E2E Cassiere associazione' (($cashiers | ForEach-Object { $_.displayName }) -join ',') 'only the new cashier can log in here'
    Invoke-Api -Client $register -Method POST -Path '/api/v1/auth/pin' -Expect 200 -Body @{ userId = $S.Cashier.id; pin = '4826' } | Out-Null
    Invoke-Api -Client $register -Method POST -Path '/api/v1/register/shift/open' -Expect 201 -Body @{ openingFloatCents = 2000 } | Out-Null

    $water = $S.Products['Acqua naturale']
    $sandwich = $S.Products['Panino con salamella']
    $total = [long]$water.priceCents + [long]$sandwich.priceCents
    $tendered = Get-TenderCents @(500, 200)
    $order = (Invoke-Api -Client $register -Method POST -Path '/api/v1/orders' -Expect 201 -Body @{
            clientOrderId = (New-ClientUuid); paymentMethod = 'CASH'; tenderedCents = $tendered; printReceipt = $true
            lines = @(@{ productId = $water.id; quantity = 1 }, @{ productId = $sandwich.id; quantity = 1 })
        }).Body
    Assert-Equal $total $order.totalCents 'total'
    Assert-Equal ($tendered - $total) $order.payment.changeCents 'change'
    Assert-Equal 1 $order.dailyNumber 'first order of the new venue'
    Assert-True (@('NOT_REQUESTED', 'NO_PRINTER') -contains $order.receipt) 'no printer yet: not printed'
    $preview = (Invoke-Api -Client $register -Path "/api/v1/register/orders/$($order.id)/receipt-preview" -Expect 200).Body
    Assert-ReceiptPreview -Preview $preview -Order $order
    Assert-True (@($preview.lines | Where-Object { $_.text.Trim() -eq 'E2E Bar dell''associazione' }).Count -eq 1) 'venue name on the receipt'

    $shift = (Invoke-Api -Client $register -Path '/api/v1/register/shift' -Expect 200).Body
    Invoke-Api -Client $register -Method POST -Path '/api/v1/register/shift/close' -Expect 200 -Body @{ countedCashCents = $shift.expectedCashCents } | Out-Null
    Invoke-Api -Client $register -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null

    $overview = (Invoke-Api -Client $S.Owner -Path "/api/v1/venues/$($S.Bar.id)/day-overview" -Expect 200).Body
    Assert-Equal 1 $overview.totals.paidOrders 'one order in the new venue today'
    Assert-Equal $total $overview.totals.cashCents 'its cash'
    Assert-Equal 1 (Invoke-Api -Client $S.Owner -Path '/api/v1/usage' -Expect 200).Body.sales 'one sale in the new tenant'
    $S.RegisterId = $device.id
}

$S.Duplicate = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'duplicate-signup'
Initialize-Csrf -Client $S.Duplicate | Out-Null
$S.DuplicateResponse = Invoke-Api -Client $S.Duplicate -Method POST -Path '/api/v1/auth/signup' `
    -Body (New-SignupBody "E2E Associazione doppia $($Ctx.Stamp)")
if ($S.DuplicateResponse.Status -eq 429) {
    Skip-Step 'Signing up again with the same email answers exactly like a new sign-up' `
        'sign-up hourly limit reached on this address before the duplicate check (429 signup.rate_limited)'
}
else {
Test-Step 'Signing up again with the same email answers exactly like a new sign-up, without a session or a change' {
    $c = $S.Duplicate
    $r = $S.DuplicateResponse
    Assert-Status $r 202
    Assert-Equal $S.AcceptedText $r.Text 'same body as a new sign-up'
    Assert-Problem (Invoke-Api -Client $c -Path '/api/v1/auth/me') 401 'unauthenticated'
    $again = Connect-Owner -BaseUrl $Ctx.BaseUrl -Email $S.Email -Password $S.Password -Name 'new-owner-again'
    Assert-Equal $S.OrgName $again.Me.tenant.name 'still the first organisation'
    Assert-Equal 2 @((Invoke-Api -Client $again -Path '/api/v1/venues' -Expect 200).Body).Count 'its venues unchanged'
    Invoke-Api -Client $again -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
}
}

Test-Step "The new tenant's register is revoked and the owner logs out" {
    $o = $S.Owner
    Invoke-Api -Client $o -Method POST -Path "/api/v1/venues/$($S.Bar.id)/devices/$($S.RegisterId)/revoke" -Expect 200 | Out-Null
    Invoke-Api -Client $o -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
}
