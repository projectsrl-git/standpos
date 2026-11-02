<#
    The register: PIN login, shift open and close, cash and card sales, idempotent retry, "senza scontrino",
    reprint, sold-out, void approved by a manager PIN, and the demo sales of data\demo-data.json.
    Sets $Ctx.UsageBefore and $Ctx.SalesCreated (orders created in this run, retries excluded).
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$register = $Ctx.Register
$base = "/api/v1/venues/$($Ctx.VenueId)"
$S = @{ Orders = @() }
$Ctx.SalesCreated = 0

function Get-OnHand([string]$ProductName) {
    $id = $Ctx.Products[$ProductName].id
    $level = @((Invoke-Api -Client $owner -Path "$base/stock" -Expect 200).Body | Where-Object { $_.productId -eq $id })
    if ($level.Count -eq 0) { return 0 }
    return [int]$level[0].onHand
}

function New-Sale {
    <# Posts an order; counts it when the backend answers 201. Returns the response. #>
    param([object[]]$Lines, [string]$Method = 'CASH', $Tendered, [bool]$Print = $true, [string]$ClientId = (New-ClientUuid))
    $body = @{
        clientOrderId = $ClientId
        lines         = @($Lines | ForEach-Object {
                $line = @{ productId = $Ctx.Products[$_.product].id; quantity = [int]$_.quantity }
                if ($_.PSObject.Properties.Name -contains 'note' -or ($_ -is [hashtable] -and $_.ContainsKey('note'))) { $line.note = $_.note }
                $line
            })
        paymentMethod = $Method
        tenderedCents = $Tendered
        printReceipt  = $Print
    }
    $r = Invoke-Api -Client $register -Method POST -Path '/api/v1/orders' -Body $body
    if ($r.Status -eq 201) {
        $Ctx.SalesCreated++
        $S.Orders += $r.Body
    }
    return $r
}

function Get-Total([object[]]$Lines) {
    $total = 0
    foreach ($l in $Lines) { $total += [long]$Ctx.Products[$l.product].priceCents * [int]$l.quantity }
    return $total
}

Test-Step 'Usage baseline read by the owner' {
    $Ctx.UsageBefore = (Invoke-Api -Client $owner -Path '/api/v1/usage' -Expect 200).Body
    Write-Info "sales this month before the run: $($Ctx.UsageBefore.sales) of $($Ctx.UsageBefore.cap)"
}

Test-Step 'Register lists the users who can log in with a PIN here' {
    $cashiers = @((Invoke-Api -Client $register -Path '/api/v1/register/cashiers' -Expect 200).Body)
    Assert-NotNull (Find-ByName $cashiers 'E2E Cassiere' -Property 'displayName') 'cashier listed'
    Assert-NotNull (Find-ByName $cashiers 'E2E Responsabile' -Property 'displayName') 'manager listed'
    Assert-True ($null -eq (Find-ByName $cashiers 'E2E Volontario festa' -Property 'displayName')) 'volunteer of another venue not listed'
}

Test-Step 'PIN login of a user of another venue answers 403 auth.pin_not_allowed' {
    $r = Invoke-Api -Client $register -Method POST -Path '/api/v1/auth/pin' -Body @{ userId = $Ctx.Users.Volunteer.Id; pin = $Ctx.Users.Volunteer.Pin }
    Assert-Problem $r 403 'auth.pin_not_allowed'
}

Test-Step 'Wrong PIN answers 401 auth.invalid_pin' {
    $wrong = if ($Ctx.Users.Cashier.Pin -eq '0000') { '1111' } else { '0000' }
    $r = Invoke-Api -Client $register -Method POST -Path '/api/v1/auth/pin' -Body @{ userId = $Ctx.Users.Cashier.Id; pin = $wrong }
    Assert-Problem $r 401 'auth.invalid_pin'
}

Test-Step 'Cashier logs in with the PIN on the register' {
    $r = Invoke-Api -Client $register -Method POST -Path '/api/v1/auth/pin' -Expect 200 -Body @{ userId = $Ctx.Users.Cashier.Id; pin = $Ctx.Users.Cashier.Pin }
    Assert-Equal 'CASHIER' $r.Body.kind 'principal kind'
    Assert-Equal $Ctx.Users.Cashier.Id $r.Body.user.id 'user'
    Assert-Equal $Ctx.RegisterId $r.Body.device.id 'device'
}

Test-Step 'Cashier cannot use the manager area (403 access_denied)' {
    Assert-Problem (Invoke-Api -Client $register -Path "$base/products") 403 'access_denied'
    Assert-Problem (Invoke-Api -Client $register -Path '/api/v1/usage') 403 'access_denied'
}

Test-Step 'No shift yet: GET shift is 204 and selling answers 409 shift.not_open' {
    $r = Invoke-Api -Client $register -Path '/api/v1/register/shift'
    Assert-Status $r 204
    $sale = New-Sale -Lines @(@{ product = 'Ghiacciolo'; quantity = 1 }) -Tendered 100
    Assert-Problem $sale 409 'shift.not_open'
}

Test-Step 'Cashier opens the shift with a 100 EUR float; a second open answers 409 shift.already_open' {
    $shift = (Invoke-Api -Client $register -Method POST -Path '/api/v1/register/shift/open' -Expect 201 -Body @{ openingFloatCents = 10000 }).Body
    Assert-Equal 10000 $shift.openingFloatCents 'float'
    Assert-Equal $Ctx.Users.Cashier.Id $shift.cashierId 'cashier'
    Assert-True ($null -eq $shift.closedAt) 'open'
    Assert-Problem (Invoke-Api -Client $register -Method POST -Path '/api/v1/register/shift/open' -Body @{ openingFloatCents = 0 }) 409 'shift.already_open'
}

Test-Step 'Register status shows the shift, the linked printer and the usage banner' {
    $status = (Invoke-Api -Client $register -Path '/api/v1/register/status' -Expect 200).Body
    Assert-NotNull $status.shift 'shift'
    Assert-NotNull $status.printer 'printer'
    Assert-Equal $Ctx.PrinterId $status.printer.id 'printer id'
    Assert-Equal 'False' $status.usage.blocked 'not blocked by the usage cap'
}

Test-Step 'Register grid groups the active products by category with stock for tracked ones' {
    $grid = (Invoke-Api -Client $register -Path '/api/v1/register/catalogue' -Expect 200).Body
    Assert-Equal $Ctx.VenueId $grid.venueId 'venue'
    foreach ($c in $Ctx.Data.categories) {
        Assert-NotNull (Find-ByName $grid.categories $c.name) "category $($c.name) on the grid"
    }
    $products = @($grid.categories | ForEach-Object { $_.products })
    $coke = Find-ByName $products 'Coca-Cola lattina'
    $beer = Find-ByName $products 'Birra media'
    Assert-True ($null -ne $coke.onHand -and $coke.onHand -gt 0) 'tracked coke has stock on the grid'
    Assert-True ($null -eq $beer.onHand) 'untracked beer has no stock count'
}

Test-Step 'Cash sale with receipt: total, change, line snapshots, stock goes down' {
    $cokeBefore = Get-OnHand 'Coca-Cola lattina'
    $lines = @(@{ product = 'Coca-Cola lattina'; quantity = 2 }, @{ product = 'Panino con salamella'; quantity = 1 })
    $total = Get-Total $lines
    $S.FirstClientId = New-ClientUuid
    $r = New-Sale -Lines $lines -Tendered 2000 -ClientId $S.FirstClientId
    Assert-Status $r 201
    $o = $r.Body
    Assert-Equal 'PAID' $o.status 'status'
    Assert-Equal $total $o.totalCents 'total'
    Assert-Equal 'CASH' $o.payment.method 'method'
    Assert-Equal 'CONFIRMED' $o.payment.status 'payment status'
    Assert-Equal 2000 $o.payment.tenderedCents 'tendered'
    Assert-Equal (2000 - $total) $o.payment.changeCents 'change'
    Assert-Equal 'Coca-Cola lattina' $o.lines[0].name 'name snapshot'
    Assert-Equal $Ctx.Products['Coca-Cola lattina'].priceCents $o.lines[0].unitPriceCents 'price snapshot'
    Assert-True ($o.dailyNumber -ge 1) 'daily number'
    Assert-Equal 'QUEUED' $o.receipt 'receipt queued for the printer'
    Assert-Equal ($cokeBefore - 2) (Get-OnHand 'Coca-Cola lattina') 'coke stock'
    $S.First = $o
    Write-Info "order #$($o.dailyNumber) of $($o.businessDate): $($o.totalCents) cents"
}

Test-Step 'Retry with the same client UUID returns the same order (200), nothing sold twice' {
    $cokeBefore = Get-OnHand 'Coca-Cola lattina'
    $lines = @(@{ product = 'Coca-Cola lattina'; quantity = 2 }, @{ product = 'Panino con salamella'; quantity = 1 })
    $r = New-Sale -Lines $lines -Tendered 2000 -ClientId $S.FirstClientId
    Assert-Status $r 200
    Assert-Equal $S.First.id $r.Body.id 'same order'
    Assert-Equal $S.First.dailyNumber $r.Body.dailyNumber 'same number'
    Assert-Equal $cokeBefore (Get-OnHand 'Coca-Cola lattina') 'stock unchanged'
}

Test-Step 'The same client UUID from another register answers 409 sales.client_id_reused' {
    $code = (Invoke-Api -Client $owner -Method POST -Path "$base/enrolment-codes" -Expect 201 -Body @{ kind = 'REGISTER' }).Body
    $second = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'register-2'
    Initialize-Csrf -Client $second | Out-Null
    Invoke-Api -Client $second -Method POST -Path '/api/v1/devices/enrol' -Expect 201 -Body @{ code = $code.code; name = "E2E Cassa 2 $($Ctx.Stamp)" } | Out-Null
    Invoke-Api -Client $second -Method POST -Path '/api/v1/auth/pin' -Expect 200 -Body @{ userId = $Ctx.Users.Cashier.Id; pin = $Ctx.Users.Cashier.Pin } | Out-Null
    $r = Invoke-Api -Client $second -Method POST -Path '/api/v1/orders' -Body @{
        clientOrderId = $S.FirstClientId; paymentMethod = 'CASH'; printReceipt = $false
        lines = @(@{ productId = $Ctx.Products['Coca-Cola lattina'].id; quantity = 2 })
    }
    Assert-Problem $r 409 'sales.client_id_reused'
    Invoke-Api -Client $second -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
}

Test-Step '"Senza scontrino": cash sale without receipt' {
    $lines = @(@{ product = 'Krapfen alla crema'; quantity = 1 }, @{ product = 'Birra media'; quantity = 1 })
    $r = New-Sale -Lines $lines -Print $false
    Assert-Status $r 201
    Assert-Equal 'NOT_REQUESTED' $r.Body.receipt 'no receipt requested'
    Assert-Equal 'False' $r.Body.receiptPrinted 'not printed'
    Assert-True ($null -eq $r.Body.payment.changeCents -or $r.Body.payment.changeCents -eq 0) 'no change without tender'
    $S.NoReceipt = $r.Body
}

Test-Step 'Reprint: the receipt of the "senza scontrino" sale is printed afterwards' {
    $r = Invoke-Api -Client $register -Method POST -Path "/api/v1/register/orders/$($S.NoReceipt.id)/receipt" -Expect 200
    Assert-Equal 'QUEUED' $r.Body.receipt 'receipt queued'
}

Test-Step 'Manual card sale with a line note' {
    $lines = @(@{ product = 'Panino con porchetta'; quantity = 2; note = 'senza salse' }, @{ product = 'Acqua naturale 0,5 L'; quantity = 2 })
    $r = New-Sale -Lines $lines -Method 'MANUAL_CARD'
    Assert-Status $r 201
    Assert-Equal 'MANUAL_CARD' $r.Body.payment.method 'method'
    Assert-Equal (Get-Total $lines) $r.Body.payment.amountCents 'amount'
    Assert-Equal 'senza salse' $r.Body.lines[0].note 'note'
}

Test-Step 'Refused sales: SumUp not yet available, tender too low, zero quantity, not enough stock' {
    Assert-Problem (New-Sale -Lines @(@{ product = 'Ghiacciolo'; quantity = 1 }) -Method 'SUMUP_CARD') 400 'sales.method_unavailable'
    Assert-Problem (New-Sale -Lines @(@{ product = 'Ghiacciolo'; quantity = 2 }) -Tendered 100) 400 'sales.insufficient_tender'
    Assert-Problem (New-Sale -Lines @(@{ product = 'Ghiacciolo'; quantity = 0 })) 400 'sales.invalid_quantity'
    $onHand = Get-OnHand 'Panino con porchetta'
    if ($onHand -lt 999) {
        $r = New-Sale -Lines @(@{ product = 'Panino con porchetta'; quantity = $onHand + 1 })
        Assert-Problem $r 409 'sales.insufficient_stock'
        Assert-Equal $onHand $r.Problem.products[0].available 'available quantity in the problem'
    }
}

Test-Step 'Sold out by hand: the grid shows it and the sale is refused, then it is back' {
    $id = $Ctx.Products['Ghiacciolo'].id
    $level = (Invoke-Api -Client $owner -Method PUT -Path "$base/products/$id/sold-out" -Expect 200 -Body @{ soldOut = $true }).Body
    Assert-Equal 'True' $level.manualSoldOut 'manual sold-out'
    try {
        $grid = (Invoke-Api -Client $register -Path '/api/v1/register/catalogue' -Expect 200).Body
        $p = Find-ByName @($grid.categories | ForEach-Object { $_.products }) 'Ghiacciolo'
        Assert-Equal 'True' $p.soldOut 'sold out on the grid'
        $r = New-Sale -Lines @(@{ product = 'Ghiacciolo'; quantity = 1 }) -Tendered 100
        Assert-Problem $r 409 'sales.product_unavailable'
        Assert-True (@($r.Problem.productIds) -contains $id) 'problem names the product'
    }
    finally {
        Invoke-Api -Client $owner -Method PUT -Path "$base/products/$id/sold-out" -Expect 200 -Body @{ soldOut = $false } | Out-Null
    }
    $grid = (Invoke-Api -Client $register -Path '/api/v1/register/catalogue' -Expect 200).Body
    Assert-Equal 'False' (Find-ByName @($grid.categories | ForEach-Object { $_.products }) 'Ghiacciolo').soldOut 'available again'
}

Test-Step 'Void approvers are the managers of the venue (and owners) with a PIN' {
    $approvers = @((Invoke-Api -Client $register -Path '/api/v1/register/approvers' -Expect 200).Body)
    Assert-NotNull (Find-ByName $approvers 'E2E Responsabile' -Property 'displayName') 'manager can approve'
    Assert-True ($null -eq (Find-ByName $approvers 'E2E Cassiere' -Property 'displayName')) 'cashier cannot approve'
}

Test-Step 'Void with a manager PIN: drink back to stock, sandwich to waste' {
    $orangeBefore = Get-OnHand 'Aranciata lattina'
    $sandwichBefore = Get-OnHand 'Panino con salamella'
    $sale = New-Sale -Lines @(@{ product = 'Aranciata lattina'; quantity = 1 }, @{ product = 'Panino con salamella'; quantity = 1 }) -Tendered 1000
    Assert-Status $sale 201
    $o = $sale.Body
    $voidLines = @(
        @{ lineId = $o.lines[0].id; disposition = 'RETURN_TO_STOCK' }
        @{ lineId = $o.lines[1].id; disposition = 'WASTE' }
    )
    $path = "/api/v1/register/orders/$($o.id)/void"

    Assert-Problem (Invoke-Api -Client $register -Method POST -Path $path -Body @{
            managerId = $Ctx.Users.Cashier.Id; pin = $Ctx.Users.Cashier.Pin; reason = 'E2E prova'; lines = $voidLines
        }) 403 'auth.not_approver'
    $wrong = if ($Ctx.Users.Manager.Pin -eq '0000') { '1111' } else { '0000' }
    Assert-Problem (Invoke-Api -Client $register -Method POST -Path $path -Body @{
            managerId = $Ctx.Users.Manager.Id; pin = $wrong; reason = 'E2E prova'; lines = $voidLines
        }) 401 'auth.invalid_pin'
    Assert-Problem (Invoke-Api -Client $register -Method POST -Path $path -Body @{
            managerId = $Ctx.Users.Manager.Id; pin = $Ctx.Users.Manager.Pin; reason = 'E2E prova'; lines = @($voidLines[0])
        }) 400 'sales.void_lines_required'

    $voided = (Invoke-Api -Client $register -Method POST -Path $path -Expect 200 -Body @{
            managerId = $Ctx.Users.Manager.Id; pin = $Ctx.Users.Manager.Pin; reason = 'E2E ordine sbagliato'; lines = $voidLines
        }).Body
    Assert-Equal 'VOIDED' $voided.status 'status'
    Assert-Equal 'E2E ordine sbagliato' $voided.voidReason 'reason'
    Assert-NotNull $voided.voidedAt 'voidedAt'
    Assert-Equal 'RETURN_TO_STOCK' $voided.lines[0].voidDisposition 'first line disposition'
    Assert-Equal 'WASTE' $voided.lines[1].voidDisposition 'second line disposition'
    Assert-Equal $orangeBefore (Get-OnHand 'Aranciata lattina') 'drink back on the shelf'
    Assert-Equal ($sandwichBefore - 1) (Get-OnHand 'Panino con salamella') 'sandwich not back'
    Assert-Problem (Invoke-Api -Client $register -Method POST -Path $path -Body @{
            managerId = $Ctx.Users.Manager.Id; pin = $Ctx.Users.Manager.Pin; reason = 'E2E di nuovo'; lines = $voidLines
        }) 409 'sales.not_voidable'
    $S.Voided = $voided
}

Test-Step 'Demo sales of the day' {
    foreach ($sale in $Ctx.Data.demoSales) {
        $tendered = if ($sale.PSObject.Properties.Name -contains 'tenderedCents') { [long]$sale.tenderedCents } else { $null }
        $r = New-Sale -Lines @($sale.lines) -Method $sale.paymentMethod -Tendered $tendered -Print ([bool]$sale.printReceipt)
        Assert-Status $r 201
        Assert-Equal (Get-Total @($sale.lines)) $r.Body.totalCents "total of order #$($r.Body.dailyNumber)"
    }
    Write-Info "$($Ctx.Data.demoSales.Count) demo orders"
}

Test-Step "Orders of the day list this run's orders, newest first" {
    $orders = @((Invoke-Api -Client $register -Path '/api/v1/register/orders' -Expect 200).Body)
    foreach ($o in $S.Orders) {
        Assert-True (@($orders | Where-Object { $_.id -eq $o.id }).Count -eq 1) "order #$($o.dailyNumber) listed"
    }
    for ($k = 1; $k -lt $orders.Count; $k++) {
        Assert-True ($orders[$k - 1].dailyNumber -gt $orders[$k].dailyNumber) 'newest first'
    }
}

Test-Step 'Shift closes with the counted cash; the difference is reported' {
    $current = (Invoke-Api -Client $register -Path '/api/v1/register/shift' -Expect 200).Body
    $paidCash = 0
    $paidCard = 0
    $mine = @($S.Orders | Where-Object { $_.id -ne $S.Voided.id })
    foreach ($o in $mine) {
        if ($o.payment.method -eq 'CASH') { $paidCash += [long]$o.totalCents } else { $paidCard += [long]$o.totalCents }
    }
    Assert-Equal $paidCash $current.cashSalesCents 'cash sales of the shift'
    Assert-Equal $paidCard $current.manualCardCents 'manual card sales of the shift'
    Assert-Equal (10000 + $paidCash) $current.expectedCashCents 'expected cash'
    Assert-Equal $mine.Count $current.paidOrders 'paid orders'
    Assert-Equal 1 $current.voidedOrders 'voided orders'

    $counted = [long]$current.expectedCashCents - 50
    $closed = (Invoke-Api -Client $register -Method POST -Path '/api/v1/register/shift/close' -Expect 200 -Body @{ countedCashCents = $counted }).Body
    Assert-NotNull $closed.closedAt 'closedAt'
    Assert-Equal $counted $closed.countedCashCents 'counted'
    Assert-Equal -50 $closed.differenceCents 'difference'
    Write-Info "shift closed: $($closed.paidOrders) paid, $($closed.voidedOrders) voided, expected $($closed.expectedCashCents) cents"
}

Test-Step 'After closing, selling answers 409 shift.not_open and closing again too' {
    Assert-Problem (New-Sale -Lines @(@{ product = 'Ghiacciolo'; quantity = 1 }) -Tendered 100) 409 'shift.not_open'
    Assert-Problem (Invoke-Api -Client $register -Method POST -Path '/api/v1/register/shift/close' -Body @{ countedCashCents = 0 }) 409 'shift.not_open'
}

Test-Step 'Cashier logs out; the register stays enrolled' {
    Invoke-Api -Client $register -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
    Assert-Problem (Invoke-Api -Client $register -Path '/api/v1/register/shift') 403 'access_denied'
    $current = (Invoke-Api -Client $register -Path '/api/v1/devices/current' -Expect 200).Body
    Assert-Equal 'True' $current.enrolled 'still enrolled'
}
