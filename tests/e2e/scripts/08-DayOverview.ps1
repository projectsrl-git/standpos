<#
    Manager area "Ordini e turni": GET /api/v1/venues/{venueId}/day-overview. The day's orders, shifts and totals
    must agree with the sales of this run (07-Register), the default date is today in the venue's time zone, and
    only owners and the venue's own managers may read it.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$base = "/api/v1/venues/$($Ctx.VenueId)"
$S = @{}

Test-Step 'Without a date the overview is today in the venue time zone, the same as ?date=today' {
    $S.Overview = (Invoke-Api -Client $owner -Path "$base/day-overview" -Expect 200).Body
    $o = $S.Overview
    Assert-Equal $Ctx.Venue.timeZone $o.timeZone 'time zone of the venue'
    Assert-Equal $o.today $o.businessDate 'default date is today'
    $expected = Get-BusinessDate -TimeZone $Ctx.Venue.timeZone -DayChangeTime $Ctx.Venue.dayChangeTime
    if ($expected) {
        Assert-Equal $expected $o.today "today at $($Ctx.Venue.timeZone) with the day change at $($Ctx.Venue.dayChangeTime)"
    }
    else {
        Write-Info "time zone $($Ctx.Venue.timeZone) not mapped on this PC: today checked against the orders only"
    }
    Assert-Equal $Ctx.RunOrders[0].businessDate $o.businessDate "business date of this run's orders"
    $explicit = (Invoke-Api -Client $owner -Path "$base/day-overview?date=$($o.businessDate)" -Expect 200).Body
    Assert-Equal $o.businessDate $explicit.businessDate 'explicit date'
    Assert-Equal $o.totals.totalCents $explicit.totals.totalCents 'same totals'
    Assert-Equal @($o.orders).Count @($explicit.orders).Count 'same orders'
}

Test-Step "Every order of this run is listed with its total, payment, status, cashier and register" {
    $rows = @($S.Overview.orders)
    foreach ($order in $Ctx.RunOrders) {
        $row = @($rows | Where-Object { $_.id -eq $order.id })
        Assert-Equal 1 $row.Count "order #$($order.dailyNumber) listed"
        $row = $row[0]
        Assert-Equal $order.dailyNumber $row.dailyNumber 'number'
        Assert-Equal $order.totalCents $row.totalCents "total of #$($order.dailyNumber)"
        Assert-Equal $order.payment.method $row.paymentMethod "payment of #$($order.dailyNumber)"
        $status = if ($order.id -eq $Ctx.VoidedOrderId) { 'VOIDED' } else { 'PAID' }
        Assert-Equal $status $row.status "status of #$($order.dailyNumber)"
        Assert-Equal $order.deviceId $row.registerId 'register'
        Assert-NotNull $row.registerName 'register name'
        Assert-NotNull $row.cashierName 'cashier name'
    }
    $voided = @($rows | Where-Object { $_.id -eq $Ctx.VoidedOrderId })[0]
    Assert-Equal 'E2E ordine sbagliato' $voided.voidReason 'void reason'
    Assert-Equal 'E2E Cassiere' (@($rows | Where-Object { $_.registerId -eq $Ctx.RegisterId -and $_.id -eq $Ctx.RunOrders[0].id })[0]).cashierName 'cashier of the first sale'
    for ($k = 1; $k -lt $rows.Count; $k++) {
        Assert-True ($rows[$k - 1].dailyNumber -gt $rows[$k].dailyNumber) 'newest first'
    }
}

Test-Step "The day's totals grew by exactly this run's sales (cash, card, paid, voided)" {
    $before = $Ctx.OverviewBefore.totals
    $after = $S.Overview.totals
    $paid = @($Ctx.RunOrders | Where-Object { $_.id -ne $Ctx.VoidedOrderId })
    $cash = 0
    $card = 0
    foreach ($o in $paid) {
        if ($o.payment.method -eq 'CASH') { $cash += [long]$o.totalCents } else { $card += [long]$o.totalCents }
    }
    Assert-Equal ([int]$before.paidOrders + $paid.Count) $after.paidOrders 'paid orders'
    Assert-Equal ([int]$before.voidedOrders + 1) $after.voidedOrders 'voided orders'
    Assert-Equal ([long]$before.cashCents + $cash) $after.cashCents 'cash'
    Assert-Equal ([long]$before.cardCents + $card) $after.cardCents 'card'
    Assert-Equal ([long]$after.cashCents + [long]$after.cardCents) $after.totalCents 'total = cash + card'
    Write-Info ("day {0}: {1} paid, {2} voided, cash {3}, card {4}" -f $S.Overview.businessDate, $after.paidOrders,
        $after.voidedOrders, (Format-Cents $after.cashCents), (Format-Cents $after.cardCents))
}

Test-Step 'Day totals agree with the order rows' {
    $rows = @($S.Overview.orders)
    $paid = @($rows | Where-Object { $_.status -eq 'PAID' })
    $cash = 0
    $card = 0
    foreach ($r in $paid) {
        if ($r.paymentMethod -eq 'CASH') { $cash += [long]$r.totalCents } else { $card += [long]$r.totalCents }
    }
    Assert-Equal $paid.Count $S.Overview.totals.paidOrders 'paid orders'
    Assert-Equal @($rows | Where-Object { $_.status -eq 'VOIDED' }).Count $S.Overview.totals.voidedOrders 'voided orders'
    Assert-Equal $cash $S.Overview.totals.cashCents 'cash'
    Assert-Equal $card $S.Overview.totals.cardCents 'card'
}

Test-Step "This run's shifts show float, sales, expected and counted cash and the difference" {
    foreach ($closed in $Ctx.ClosedShifts) {
        $row = @($S.Overview.shifts | Where-Object { $_.id -eq $closed.id })
        Assert-Equal 1 $row.Count 'shift listed'
        $row = $row[0]
        foreach ($field in 'openingFloatCents', 'paidOrders', 'voidedOrders', 'cashSalesCents', 'manualCardCents',
            'expectedCashCents', 'countedCashCents', 'differenceCents') {
            Assert-Equal $closed.$field $row.$field "$field of the shift on $($row.registerName)"
        }
        Assert-NotNull $row.closedAt 'closed'
        Assert-Equal ([long]$row.countedCashCents - [long]$row.expectedCashCents) $row.differenceCents 'difference = counted - expected'
    }
    $first = @($S.Overview.shifts | Where-Object { $_.registerId -eq $Ctx.RegisterId })[0]
    Assert-Equal 'E2E Cassiere' $first.cashierName 'cashier of the bar register'
    $second = @($S.Overview.shifts | Where-Object { $_.registerId -eq $Ctx.Register2Id })[0]
    Assert-Equal 'E2E Responsabile' $second.cashierName 'manager on the second register'
}

Test-Step 'Totals per cashier add up their shifts' {
    foreach ($c in @($S.Overview.cashiers)) {
        $shifts = @($S.Overview.shifts | Where-Object { $_.cashierId -eq $c.cashierId })
        $cash = 0; $card = 0; $paidOrders = 0; $voidedOrders = 0; $diff = $null
        # Not $s: variable names are case-insensitive and $S holds the script's state.
        foreach ($shift in $shifts) {
            $cash += [long]$shift.cashSalesCents; $card += [long]$shift.manualCardCents
            $paidOrders += [int]$shift.paidOrders; $voidedOrders += [int]$shift.voidedOrders
            if ($null -ne $shift.differenceCents) { $diff = [long]$diff + [long]$shift.differenceCents }
        }
        Assert-Equal $shifts.Count $c.shifts "shifts of $($c.cashierName)"
        Assert-Equal $paidOrders $c.paidOrders "paid orders of $($c.cashierName)"
        Assert-Equal $voidedOrders $c.voidedOrders "voided orders of $($c.cashierName)"
        Assert-Equal $cash $c.cashSalesCents "cash of $($c.cashierName)"
        Assert-Equal $card $c.manualCardCents "card of $($c.cashierName)"
        Assert-Equal ($cash + $card) $c.totalCents "total of $($c.cashierName)"
        Assert-Equal $diff $c.differenceCents "difference of $($c.cashierName)"
    }
    Assert-NotNull (Find-ByName $S.Overview.cashiers 'E2E Cassiere' -Property 'cashierName') 'the cashier has a totals row'
}

Test-Step 'A day without sales is empty' {
    $empty = (Invoke-Api -Client $owner -Path "$base/day-overview?date=2000-01-01" -Expect 200).Body
    Assert-Equal '2000-01-01' $empty.businessDate 'date'
    Assert-Equal 0 @($empty.orders).Count 'orders'
    Assert-Equal 0 @($empty.shifts).Count 'shifts'
    Assert-Equal 0 $empty.totals.totalCents 'total'
    Assert-Problem (Invoke-Api -Client $owner -Path "$base/day-overview?date=not-a-date") 400 'request.invalid'
}

Test-Step 'The manager of another venue cannot read this venue (403), only their own' {
    $u = $Ctx.Users.EventManager
    $manager = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'event-manager'
    Initialize-Csrf -Client $manager | Out-Null
    $login = Invoke-Login -Client $manager -Body @{ email = $u.Email; password = $u.Password }
    Assert-Status $login 200
    Assert-Problem (Invoke-Api -Client $manager -Path "$base/day-overview") 403 'access_denied'
    Assert-Problem (Invoke-Api -Client $manager -Path "$base/orders/$($Ctx.RunOrders[0].id)/receipt-preview") 403 'access_denied'
    $own = (Invoke-Api -Client $manager -Path "/api/v1/venues/$($Ctx.EventVenueId)/day-overview" -Expect 200).Body
    Assert-Equal 0 @($own.orders | Where-Object { $_.registerId -eq $Ctx.RegisterId }).Count "no orders of the bar in the event's overview"
    Invoke-Api -Client $manager -Method POST -Path '/api/v1/auth/logout' -Expect 204 | Out-Null
}
