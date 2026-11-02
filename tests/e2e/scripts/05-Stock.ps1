<#
    Stock: suppliers, goods receipts (draft, edit, post), adjustments and waste, a stock count, movements and the
    low-stock list. Documents written by the suite carry "E2E" in their reference, notes or reason.
    Sets $Ctx.Suppliers.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$base = "/api/v1/venues/$($Ctx.VenueId)"
$data = $Ctx.Data
$Ctx.Suppliers = @{}
$S = @{}

function Get-Levels {
    $levels = @{}
    foreach ($l in @((Invoke-Api -Client $owner -Path "$base/stock" -Expect 200).Body)) { $levels[$l.productId] = $l }
    return $levels
}

function Get-OnHand([string]$ProductName) {
    $id = $Ctx.Products[$ProductName].id
    $level = (Get-Levels)[$id]
    if ($null -eq $level) { return 0 }
    return [int]$level.onHand
}

Test-Step 'Leftover E2E drafts of interrupted runs are removed' {
    foreach ($r in @((Invoke-Api -Client $owner -Path "$base/goods-receipts" -Expect 200).Body)) {
        if ($r.status -eq 'DRAFT' -and ([string]$r.documentRef).StartsWith('E2E-')) {
            Invoke-Api -Client $owner -Method DELETE -Path "$base/goods-receipts/$($r.id)" -Expect 204 | Out-Null
            Write-Info "deleted draft goods receipt $($r.documentRef)"
        }
    }
    foreach ($c in @((Invoke-Api -Client $owner -Path "$base/stock-counts" -Expect 200).Body)) {
        if ($c.status -eq 'DRAFT' -and ([string]$c.notes).StartsWith('E2E')) {
            Invoke-Api -Client $owner -Method DELETE -Path "$base/stock-counts/$($c.id)" -Expect 204 | Out-Null
            Write-Info 'deleted a draft stock count'
        }
    }
}

Test-Step 'Suppliers exist (created or reused by name)' {
    $existing = @((Invoke-Api -Client $owner -Path '/api/v1/suppliers' -Expect 200).Body)
    foreach ($supplier in $data.suppliers) {
        $found = Find-ByName $existing $supplier.name
        if ($null -eq $found) {
            $found = (Invoke-Api -Client $owner -Method POST -Path '/api/v1/suppliers' -Expect 201 -Body @{
                    name = $supplier.name; contactName = $supplier.contactName; phone = $supplier.phone
                    email = $supplier.email; notes = $supplier.notes; active = $true
                }).Body
            Write-Info "created supplier $($supplier.name)"
        }
        Assert-True ([bool]$found.active) "$($supplier.name) active"
        $Ctx.Suppliers[$supplier.name] = $found
    }
}

Test-Step 'Supplier with an invalid email answers 400 validation' {
    $r = Invoke-Api -Client $owner -Method POST -Path '/api/v1/suppliers' -Body @{ name = 'E2E fornitore'; email = 'non-una-mail' }
    Assert-Problem $r 400 'validation'
}

Test-Step 'Goods receipt for an untracked product answers 400 stock.product_not_tracked' {
    $r = Invoke-Api -Client $owner -Method POST -Path "$base/goods-receipts" -Body @{
        supplierId = $Ctx.Suppliers['Bevande Rossi srl'].id; documentRef = "E2E-BAD-$($Ctx.Stamp)"; receivedOn = (Get-Today)
        lines = @(@{ productId = $Ctx.Products['Birra media'].id; quantity = 10; unitCostCents = 100 })
    }
    Assert-Problem $r 400 'stock.product_not_tracked'
}

Test-Step 'Empty draft goods receipt cannot be posted (400 stock.receipt_empty) and can be deleted' {
    $draft = (Invoke-Api -Client $owner -Method POST -Path "$base/goods-receipts" -Expect 201 -Body @{
            documentRef = "E2E-EMPTY-$($Ctx.Stamp)"; receivedOn = (Get-Today); notes = 'E2E bozza vuota'; lines = @()
        }).Body
    Assert-Equal 'DRAFT' $draft.status 'status'
    Assert-Problem (Invoke-Api -Client $owner -Method POST -Path "$base/goods-receipts/$($draft.id)/post") 400 'stock.receipt_empty'
    Invoke-Api -Client $owner -Method DELETE -Path "$base/goods-receipts/$($draft.id)" -Expect 204 | Out-Null
    Assert-Problem (Invoke-Api -Client $owner -Path "$base/goods-receipts/$($draft.id)") 404 'not_found'
}

$i = 0
foreach ($receipt in $data.goodsReceipts) {
    $i++
    Test-Step "Goods receipt from '$($receipt.supplier)': draft, edit, post, stock goes up" {
        $before = Get-Levels
        $lines = @($receipt.lines | ForEach-Object {
                @{ productId = $Ctx.Products[$_.product].id; quantity = [int]$_.quantity; unitCostCents = [long]$_.unitCostCents }
            })
        $body = @{
            supplierId = $Ctx.Suppliers[$receipt.supplier].id; documentRef = "E2E-DDT-$($Ctx.Stamp)-$i"
            receivedOn = (Get-Today); notes = "E2E $($receipt.notes)"; lines = $lines
        }
        # Draft with the first line short by 2, then corrected: the edit is what gets posted.
        $lines[0].quantity = [int]$receipt.lines[0].quantity - 2
        $draft = (Invoke-Api -Client $owner -Method POST -Path "$base/goods-receipts" -Expect 201 -Body $body).Body
        Assert-Equal 'DRAFT' $draft.status 'draft status'
        $lines[0].quantity = [int]$receipt.lines[0].quantity
        $edited = (Invoke-Api -Client $owner -Method PUT -Path "$base/goods-receipts/$($draft.id)" -Expect 200 -Body $body).Body
        $expectedTotal = 0
        foreach ($l in $receipt.lines) { $expectedTotal += [long]$l.quantity * [long]$l.unitCostCents }
        Assert-Equal $expectedTotal $edited.totalCostCents 'total cost in cents'

        $posted = (Invoke-Api -Client $owner -Method POST -Path "$base/goods-receipts/$($draft.id)/post" -Expect 200).Body
        Assert-Equal 'POSTED' $posted.status 'posted status'
        Assert-NotNull $posted.postedAt 'postedAt'

        $after = Get-Levels
        foreach ($l in $receipt.lines) {
            $id = $Ctx.Products[$l.product].id
            $was = if ($before.ContainsKey($id)) { [int]$before[$id].onHand } else { 0 }
            Assert-Equal ($was + [int]$l.quantity) $after[$id].onHand "stock of $($l.product)"
        }
        $S["Receipt$i"] = $posted
        Write-Info "posted $($posted.documentRef): $(@($posted.lines).Count) lines, $($posted.totalCostCents) cents"
    }
}

Test-Step 'Posted goods receipt cannot change (409 stock.receipt_posted)' {
    $posted = $S['Receipt1']
    Assert-Problem (Invoke-Api -Client $owner -Method POST -Path "$base/goods-receipts/$($posted.id)/post") 409 'stock.receipt_posted'
    Assert-Problem (Invoke-Api -Client $owner -Method DELETE -Path "$base/goods-receipts/$($posted.id)") 409 'stock.receipt_posted'
}

Test-Step 'Supplier with goods receipts cannot be deleted (409 stock.supplier_in_use)' {
    $r = Invoke-Api -Client $owner -Method DELETE -Path "/api/v1/suppliers/$($Ctx.Suppliers['Bevande Rossi srl'].id)"
    Assert-Problem $r 409 'stock.supplier_in_use'
}

Test-Step 'Adjustment: one damaged can removed with a reason' {
    $was = Get-OnHand 'Coca-Cola lattina'
    $level = (Invoke-Api -Client $owner -Method POST -Path "$base/stock/adjustments" -Expect 201 -Body @{
            productId = $Ctx.Products['Coca-Cola lattina'].id; type = 'ADJUSTMENT'; quantity = -1; reason = 'E2E lattina ammaccata'
        }).Body
    Assert-Equal ($was - 1) $level.onHand 'on hand after adjustment'
}

Test-Step 'Waste: two expired krapfen thrown away' {
    $was = Get-OnHand 'Krapfen alla crema'
    $level = (Invoke-Api -Client $owner -Method POST -Path "$base/stock/adjustments" -Expect 201 -Body @{
            productId = $Ctx.Products['Krapfen alla crema'].id; type = 'WASTE'; quantity = 2; reason = 'E2E scaduti'
        }).Body
    Assert-Equal ($was - 2) $level.onHand 'on hand after waste'
}

Test-Step 'Hand-entered movements other than ADJUSTMENT/WASTE are refused (400 stock.invalid_type)' {
    $r = Invoke-Api -Client $owner -Method POST -Path "$base/stock/adjustments" -Body @{
        productId = $Ctx.Products['Ghiacciolo'].id; type = 'SALE'; quantity = 1; reason = 'E2E'
    }
    Assert-Problem $r 400 'stock.invalid_type'
}

Test-Step 'Zero quantity answers 400 stock.invalid_quantity; missing reason 400 validation' {
    Assert-Problem (Invoke-Api -Client $owner -Method POST -Path "$base/stock/adjustments" -Body @{
            productId = $Ctx.Products['Ghiacciolo'].id; type = 'ADJUSTMENT'; quantity = 0; reason = 'E2E'
        }) 400 'stock.invalid_quantity'
    Assert-Problem (Invoke-Api -Client $owner -Method POST -Path "$base/stock/adjustments" -Body @{
            productId = $Ctx.Products['Ghiacciolo'].id; type = 'WASTE'; quantity = 1; reason = ''
        }) 400 'validation'
}

Test-Step 'Waste beyond the stock answers 409 stock.insufficient' {
    $onHand = Get-OnHand 'Ghiacciolo'
    $r = Invoke-Api -Client $owner -Method POST -Path "$base/stock/adjustments" -Body @{
        productId = $Ctx.Products['Ghiacciolo'].id; type = 'WASTE'; quantity = $onHand + 1; reason = 'E2E troppi'
    }
    Assert-Problem $r 409 'stock.insufficient'
}

Test-Step 'Stock count: open, count one bottle less, post, COUNT movement written' {
    $count = (Invoke-Api -Client $owner -Method POST -Path "$base/stock-counts" -Expect 201 -Body @{ notes = "E2E conta $($Ctx.Stamp)" }).Body
    Assert-Equal 'DRAFT' $count.status 'count status'
    $waterId = $Ctx.Products['Acqua naturale 0,5 L'].id
    $line = @($count.lines | Where-Object { $_.productId -eq $waterId })
    Assert-Equal 1 $line.Count 'the count lists the tracked water'
    Assert-True (@($count.lines | Where-Object { $_.productId -eq $Ctx.Products['Birra media'].id }).Count -eq 0) 'untracked beer not counted'
    $expected = [int]$line[0].expected

    $entered = (Invoke-Api -Client $owner -Method PUT -Path "$base/stock-counts/$($count.id)" -Expect 200 -Body @{
            lines = @(@{ productId = $waterId; counted = $expected - 1 })
        }).Body
    Assert-Equal ($expected - 1) (@($entered.lines | Where-Object { $_.productId -eq $waterId })[0].counted) 'counted quantity'
    Assert-Problem (Invoke-Api -Client $owner -Method PUT -Path "$base/stock-counts/$($count.id)" -Body @{
            lines = @(@{ productId = $waterId; counted = -1 })
        }) 400 'stock.invalid_quantity'

    $posted = (Invoke-Api -Client $owner -Method POST -Path "$base/stock-counts/$($count.id)/post" -Expect 200).Body
    Assert-Equal 'POSTED' $posted.status 'posted'
    Assert-Equal -1 (@($posted.lines | Where-Object { $_.productId -eq $waterId })[0].difference) 'difference'
    Assert-Equal ($expected - 1) (Get-OnHand 'Acqua naturale 0,5 L') 'on hand after the count'
    Assert-Problem (Invoke-Api -Client $owner -Method POST -Path "$base/stock-counts/$($count.id)/post") 409 'stock.count_posted'
}

Test-Step 'Movements of the water show receipt and count, newest first' {
    $waterId = $Ctx.Products['Acqua naturale 0,5 L'].id
    $moves = @((Invoke-Api -Client $owner -Path "$base/stock/movements?productId=$waterId&limit=20" -Expect 200).Body)
    Assert-True ($moves.Count -ge 2) 'at least two movements'
    Assert-Equal 'COUNT' $moves[0].type 'latest movement is the count'
    Assert-Equal -1 $moves[0].quantity 'count quantity'
    Assert-True (@($moves | Where-Object { $_.type -eq 'RECEIPT' }).Count -ge 1) 'a RECEIPT movement'
    Assert-Equal (Get-OnHand 'Acqua naturale 0,5 L') $moves[0].onHandAfter 'onHandAfter of the latest movement'
}

Test-Step 'Low-stock list matches the levels flagged low' {
    $levels = @((Invoke-Api -Client $owner -Path "$base/stock" -Expect 200).Body)
    $low = @((Invoke-Api -Client $owner -Path "$base/stock/low" -Expect 200).Body)
    $flagged = @($levels | Where-Object { $_.low } | ForEach-Object { $_.productId } | Sort-Object)
    $listed = @($low | ForEach-Object { $_.productId } | Sort-Object)
    Assert-Equal ($flagged -join ',') ($listed -join ',') 'low list'
    foreach ($l in $levels) {
        Assert-True ($l.valueCents -ge 0) "stock value of $($l.productName) not negative"
    }
    Write-Info "$($levels.Count) stock levels, $($low.Count) low"
}
