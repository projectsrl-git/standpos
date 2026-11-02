<#
    Devices: enrolment codes, a register enrolled from a "browser" (device cookie), a print agent enrolled on
    /api/v1/agents/enrol (bearer token), a printer of that agent linked to the register, a test page.
    Devices of earlier runs ("E2E ...") are revoked first. Sets $Ctx.Register (client), $Ctx.RegisterId,
    $Ctx.Agent (client), $Ctx.AgentId, $Ctx.PrinterId.
#>
param([Parameter(Mandatory)][hashtable]$Ctx)
$ErrorActionPreference = 'Stop'

$owner = $Ctx.OwnerClient
$base = "/api/v1/venues/$($Ctx.VenueId)"
$S = @{ RegisterName = "E2E Cassa $($Ctx.Stamp)"; AgentName = "E2E Agente $($Ctx.Stamp)"; PrinterName = 'E2E Stampante' }

function New-Code([string]$Kind) {
    $code = (Invoke-Api -Client $owner -Method POST -Path "$base/enrolment-codes" -Expect 201 -Body @{ kind = $Kind }).Body
    Assert-NotNull $code.code "$Kind code"
    Assert-Equal $Kind $code.kind 'code kind'
    return $code
}

Test-Step 'Devices of earlier E2E runs are revoked' {
    foreach ($d in @((Invoke-Api -Client $owner -Path "$base/devices" -Expect 200).Body)) {
        if ($null -eq $d.revokedAt -and ([string]$d.name).StartsWith('E2E ')) {
            $revoked = (Invoke-Api -Client $owner -Method POST -Path "$base/devices/$($d.id)/revoke" -Expect 200).Body
            Assert-NotNull $revoked.revokedAt "revokedAt of $($d.name)"
            Write-Info "revoked $($d.name)"
        }
    }
}

Test-Step 'A browser that is not enrolled is told so' {
    $Ctx.Register = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'register'
    Initialize-Csrf -Client $Ctx.Register | Out-Null
    $current = (Invoke-Api -Client $Ctx.Register -Path '/api/v1/devices/current' -Expect 200).Body
    Assert-Equal 'False' $current.enrolled 'enrolled'
    Assert-Problem (Invoke-Api -Client $Ctx.Register -Path '/api/v1/register/cashiers') 401 'unauthenticated'
}

Test-Step 'A print agent code is refused by the browser enrolment (400 device.agent_code) and not burnt' {
    $S.AgentCode = New-Code 'PRINT_AGENT'
    $r = Invoke-Api -Client $Ctx.Register -Method POST -Path '/api/v1/devices/enrol' -Body @{ code = $S.AgentCode.code; name = $S.RegisterName }
    Assert-Problem $r 400 'device.agent_code'
}

Test-Step 'Register enrols with a REGISTER code and gets an HttpOnly device cookie' {
    $code = New-Code 'REGISTER'
    $S.RegisterCode = $code.code
    $r = Invoke-Api -Client $Ctx.Register -Method POST -Path '/api/v1/devices/enrol' -Expect 201 -Body @{ code = $code.code; name = $S.RegisterName }
    Assert-Equal 'REGISTER' $r.Body.device.kind 'device kind'
    Assert-Equal $Ctx.VenueId $r.Body.device.venueId 'device venue'
    Assert-True ($r.Body.PSObject.Properties.Name -notcontains 'token') 'no token in the browser response body'
    $cookie = Get-ClientCookie -Client $Ctx.Register -Name 'SP_DEVICE'
    Assert-NotNull $cookie 'SP_DEVICE cookie'
    Assert-True ([bool]$cookie.HttpOnly) 'SP_DEVICE is HttpOnly'
    $Ctx.RegisterId = $r.Body.device.id
}

Test-Step 'The enrolled browser now reads as a REGISTER of the venue' {
    $current = (Invoke-Api -Client $Ctx.Register -Path '/api/v1/devices/current' -Expect 200).Body
    Assert-Equal 'True' $current.enrolled 'enrolled'
    Assert-Equal 'REGISTER' $current.kind 'kind'
    Assert-Equal $Ctx.RegisterId $current.id 'device id'
    Assert-Equal $S.RegisterName $current.name 'name'
}

Test-Step 'A used code cannot enrol again (400 device.invalid_code)' {
    $other = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'second-browser'
    Initialize-Csrf -Client $other | Out-Null
    $r = Invoke-Api -Client $other -Method POST -Path '/api/v1/devices/enrol' -Body @{ code = $S.RegisterCode; name = 'E2E second' }
    Assert-Problem $r 400 'device.invalid_code'
}

Test-Step 'A REGISTER code is refused by the agent enrolment (400 device.not_agent_code)' {
    $code = New-Code 'REGISTER'
    $r = Invoke-Api -Client (New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'agent-wrong') -Method POST -Path '/api/v1/agents/enrol' `
        -Body @{ code = $code.code; name = 'E2E wrong' }
    Assert-Problem $r 400 'device.not_agent_code'
}

Test-Step 'Print agent enrols with its code, without CSRF or cookies, and receives a token once' {
    $plain = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'agent-enrol'
    $r = Invoke-Api -Client $plain -Method POST -Path '/api/v1/agents/enrol' -Expect 201 -Body @{ code = $S.AgentCode.code; name = $S.AgentName }
    Assert-Equal 'PRINT_AGENT' $r.Body.device.kind 'kind'
    Assert-NotNull $r.Body.token 'token'
    Assert-True ($r.Body.token.Length -ge 40) 'token is long and random'
    Assert-True ($null -eq (Get-ClientCookie -Client $plain -Name 'SP_DEVICE')) 'no device cookie for the agent'
    $Ctx.AgentId = $r.Body.device.id
    $Ctx.Agent = New-ApiClient -BaseUrl $Ctx.BaseUrl -Name 'agent' -BearerToken $r.Body.token
}

Test-Step 'The agent authenticates with its bearer token' {
    $current = (Invoke-Api -Client $Ctx.Agent -Path '/api/v1/devices/current' -Expect 200).Body
    Assert-Equal 'True' $current.enrolled 'enrolled'
    Assert-Equal 'PRINT_AGENT' $current.kind 'kind'
    Assert-Equal $Ctx.AgentId $current.id 'agent id'
}

Test-Step 'Device list shows the register and the agent; the register can be renamed' {
    $devices = @((Invoke-Api -Client $owner -Path "$base/devices" -Expect 200).Body)
    Assert-NotNull (@($devices | Where-Object { $_.id -eq $Ctx.RegisterId })[0]) 'register listed'
    Assert-NotNull (@($devices | Where-Object { $_.id -eq $Ctx.AgentId })[0]) 'agent listed'
    $S.RegisterName = "E2E Cassa bar $($Ctx.Stamp)"
    $renamed = (Invoke-Api -Client $owner -Method PUT -Path "$base/devices/$($Ctx.RegisterId)" -Expect 200 -Body @{ name = $S.RegisterName }).Body
    Assert-Equal $S.RegisterName $renamed.name 'renamed'
}

Test-Step "Printer '$($S.PrinterName)' belongs to the new agent (created or reused)" {
    $printers = @((Invoke-Api -Client $owner -Path "$base/printers" -Expect 200).Body)
    $printer = Find-ByName $printers $S.PrinterName
    $body = @{ agentId = $Ctx.AgentId; name = $S.PrinterName; windowsName = 'text:e2e-receipts'; widthMm = 80; codePage = 'CP858'; active = $true }
    if ($null -eq $printer) {
        $printer = (Invoke-Api -Client $owner -Method POST -Path "$base/printers" -Expect 201 -Body $body).Body
        Write-Info 'created the printer'
    }
    else {
        $printer = (Invoke-Api -Client $owner -Method PUT -Path "$base/printers/$($printer.id)" -Expect 200 -Body $body).Body
    }
    Assert-Equal $Ctx.AgentId $printer.agentId 'agent of the printer'
    Assert-Equal $S.AgentName $printer.agentName 'agent name'
    Assert-Equal 80 $printer.widthMm 'width'
    $Ctx.PrinterId = $printer.id
}

Test-Step 'Paper width other than 58/80 mm answers 400 printing.invalid_width' {
    $r = Invoke-Api -Client $owner -Method PUT -Path "$base/printers/$($Ctx.PrinterId)" -Body @{
        agentId = $Ctx.AgentId; name = $S.PrinterName; windowsName = 'text:e2e-receipts'; widthMm = 70
    }
    Assert-Problem $r 400 'printing.invalid_width'
}

Test-Step 'Printer of a register (not an agent) answers 400 printing.invalid_agent' {
    $r = Invoke-Api -Client $owner -Method PUT -Path "$base/printers/$($Ctx.PrinterId)" -Body @{
        agentId = $Ctx.RegisterId; name = $S.PrinterName; windowsName = 'text:e2e-receipts'
    }
    Assert-Problem $r 400 'printing.invalid_agent'
}

Test-Step 'Register is linked to the printer; a test page is queued' {
    $link = (Invoke-Api -Client $owner -Method PUT -Path "$base/devices/$($Ctx.RegisterId)/printer" -Expect 200 -Body @{ printerId = $Ctx.PrinterId }).Body
    Assert-Equal $Ctx.PrinterId $link.printerId 'linked printer'
    $test = (Invoke-Api -Client $owner -Method POST -Path "$base/printers/$($Ctx.PrinterId)/test" -Expect 201).Body
    Assert-NotNull $test.jobId 'test job id'
    Assert-True (@('QUEUED', 'SENT', 'PRINTED') -contains $test.status) "test job status $($test.status)"
    Assert-Problem (Invoke-Api -Client $owner -Method DELETE -Path "$base/printers/$($Ctx.PrinterId)") 409 'printing.printer_in_use'
}
