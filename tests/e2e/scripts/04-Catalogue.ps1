<#
    Catalogue of the venue under test from data\demo-data.json: routes (bar, kitchen), categories, products with
    prices in integer cents, some with stock tracking. Existing entries are reused by name; on reuse only the
    flags the later scripts rely on are corrected (active, stock tracking, not sold out).
    Sets $Ctx.Routes, $Ctx.Categories and $Ctx.Products (name -> API object).
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$base = "/api/v1/venues/$($Ctx.VenueId)"
$data = $Ctx.Data
$Ctx.Routes = @{}
$Ctx.Categories = @{}
$Ctx.Products = @{}

Test-Step 'Routes exist (bar and kitchen)' {
    $existing = @((Invoke-Api -Client $owner -Path "$base/routes" -Expect 200).Body)
    foreach ($route in $data.routes) {
        $found = Find-ByName $existing $route.name
        if ($null -eq $found) {
            $found = (Invoke-Api -Client $owner -Method POST -Path "$base/routes" -Expect 201 -Body @{ name = $route.name; sort = $route.sort }).Body
            Write-Info "created route $($route.name)"
        }
        $Ctx.Routes[$route.name] = $found
    }
    Assert-Equal $data.routes.Count $Ctx.Routes.Count 'routes'
}

Test-Step 'Categories exist with colour and default route' {
    $existing = @((Invoke-Api -Client $owner -Path "$base/categories" -Expect 200).Body)
    foreach ($category in $data.categories) {
        $found = Find-ByName $existing $category.name
        if ($null -eq $found) {
            $found = (Invoke-Api -Client $owner -Method POST -Path "$base/categories" -Expect 201 -Body @{
                    name = $category.name; colour = $category.colour; sort = $category.sort
                    defaultRouteId = $Ctx.Routes[$category.route].id
                }).Body
            Write-Info "created category $($category.name)"
            Assert-Equal $category.colour.ToUpperInvariant() $found.colour 'colour stored upper case'
        }
        $Ctx.Categories[$category.name] = $found
    }
}

Test-Step 'Products exist with integer-cent prices and stock tracking' {
    $existing = @((Invoke-Api -Client $owner -Path "$base/products" -Expect 200).Body)
    $created = 0
    foreach ($product in $data.products) {
        $found = Find-ByName $existing $product.name
        $tracked = [bool]$product.trackStock
        $low = if ($product.PSObject.Properties.Name -contains 'lowThreshold') { $product.lowThreshold } else { $null }
        $routeId = if ($product.PSObject.Properties.Name -contains 'route') { $Ctx.Routes[$product.route].id } else { $null }
        if ($null -eq $found) {
            $found = (Invoke-Api -Client $owner -Method POST -Path "$base/products" -Expect 201 -Body @{
                    categoryId = $Ctx.Categories[$product.category].id; name = $product.name
                    priceCents = [long]$product.priceCents; routeId = $routeId; active = $true; trackStock = $tracked
                    lowThreshold = $low; soldOut = $false; sort = $product.sort
                }).Body
            $created++
            Assert-Equal $product.priceCents $found.priceCents "price of $($product.name)"
        }
        elseif (-not $found.active -or [bool]$found.trackStock -ne $tracked -or $found.soldOut) {
            $found = (Invoke-Api -Client $owner -Method PUT -Path "$base/products/$($found.id)" -Expect 200 -Body @{
                    categoryId = $found.categoryId; name = $found.name; priceCents = [long]$found.priceCents
                    routeId = $found.routeId; active = $true; trackStock = $tracked; lowThreshold = $found.lowThreshold
                    soldOut = $false; sort = $found.sort
                }).Body
            Write-Info "restored the flags of $($product.name)"
        }
        $Ctx.Products[$product.name] = $found
    }
    Write-Info "$created products created, $($data.products.Count - $created) reused"
}

Test-Step 'Product list shows every demo product, active, with its category' {
    $list = @((Invoke-Api -Client $owner -Path "$base/products" -Expect 200).Body)
    foreach ($product in $data.products) {
        $p = Find-ByName $list $product.name
        Assert-NotNull $p "product $($product.name)"
        Assert-True ([bool]$p.active) "$($product.name) active"
        Assert-Equal $Ctx.Categories[$product.category].id $p.categoryId "category of $($product.name)"
        Assert-True ($p.priceCents -is [int] -or $p.priceCents -is [long]) "integer price of $($product.name)"
    }
}

Test-Step 'Category with an invalid colour answers 400 validation' {
    $r = Invoke-Api -Client $owner -Method POST -Path "$base/categories" -Body @{ name = 'E2E colore'; colour = 'blu'; sort = 1 }
    Assert-Problem $r 400 'validation'
    Assert-True (@($r.Problem.errors | Where-Object { $_.field -eq 'colour' }).Count -eq 1) 'colour field error'
}

Test-Step 'Negative price answers 400 validation' {
    $r = Invoke-Api -Client $owner -Method POST -Path "$base/products" -Body @{
        categoryId = $Ctx.Categories['Bibite'].id; name = 'E2E prezzo negativo'; priceCents = -100; active = $true; sort = 1
    }
    Assert-Problem $r 400 'validation'
}

Test-Step 'Category that still has products cannot be deleted (409 catalog.category_not_empty)' {
    $r = Invoke-Api -Client $owner -Method DELETE -Path "$base/categories/$($Ctx.Categories['Bibite'].id)"
    Assert-Problem $r 409 'catalog.category_not_empty'
}

Test-Step 'Route in use cannot be deleted (409 catalog.route_in_use)' {
    $r = Invoke-Api -Client $owner -Method DELETE -Path "$base/routes/$($Ctx.Routes['Cucina'].id)"
    Assert-Problem $r 409 'catalog.route_in_use'
}

Test-Step 'Unused product: create, deactivate, delete, then 404' {
    $p = (Invoke-Api -Client $owner -Method POST -Path "$base/products" -Expect 201 -Body @{
            categoryId = $Ctx.Categories['Dolci'].id; name = "E2E prodotto temporaneo $($Ctx.Stamp)"; priceCents = 50
            active = $true; trackStock = $false; soldOut = $false; sort = 999
        }).Body
    Assert-Equal 'False' $p.inUse 'new product not in use'
    $d = (Invoke-Api -Client $owner -Method POST -Path "$base/products/$($p.id)/deactivate" -Expect 200).Body
    Assert-Equal 'False' $d.active 'deactivated'
    Invoke-Api -Client $owner -Method DELETE -Path "$base/products/$($p.id)" -Expect 204 | Out-Null
    $again = Invoke-Api -Client $owner -Method DELETE -Path "$base/products/$($p.id)"
    Assert-Problem $again 404 'not_found'
}
