<#
.SYNOPSIS
    Builds the StandPOS print agent for Windows: a self-contained app image (jpackage, with its own Java runtime),
    the WinSW service wrapper and the install scripts, zipped.

.DESCRIPTION
    Output: print-agent\target\StandPOS-PrintAgent-<version>-windows-x64.zip containing
        install.ps1, uninstall.ps1
        StandPOS Print Agent\                 jpackage app image (StandPOS Print Agent.exe, app\, runtime\)
        StandPOS Print Agent\StandPOSPrintAgent.exe / .xml   WinSW 2.x and its configuration

    Requires JDK 25 (with jpackage) in -JavaHome or JAVA_HOME. Run on Windows x64.

.EXAMPLE
    .\build.ps1
    .\build.ps1 -SkipMaven -SkipWinSW     # reuse target\ and build only the app image (no zip)
#>
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    # Skip the Maven build and reuse print-agent\target (after `mvn -pl print-agent -am package`)
    [switch]$SkipMaven,
    [switch]$SkipTests,
    # Build the app image only: no WinSW, no zip (for checking jpackage without network access)
    [switch]$SkipWinSW,
    # An already downloaded WinSW-x64.exe; it is verified against the pinned hash all the same
    [string]$WinSWPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# WinSW 2.x is the stable line (3.x is still alpha). GitHub publishes no digest for this release, so the SHA-256
# was pinned on 2026-10-26 from the release asset (18,243,033 bytes, uploaded 2023-01-28, file version 2.12.0.0,
# not Authenticode-signed), computed with sha256sum and Get-FileHash. A new WinSW version needs a new pin.
$WinSWVersion = 'v2.12.0'
$WinSWUrl = "https://github.com/winsw/winsw/releases/download/$WinSWVersion/WinSW-x64.exe"
$WinSWSha256 = '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'

$ServiceId = 'StandPOSPrintAgent'
$AppName = 'StandPOS Print Agent'
$MainClass = 'it.projectsrl.standpos.printagent.Main'
# From `jdeps --print-module-deps` (java.base, java.desktop for javax.print and ImageIO, java.net.http, java.sql)
# plus java.logging (JUL), jdk.charsets (extra code pages) and jdk.crypto.ec (EC TLS cipher suites).
$Modules = 'java.base,java.desktop,java.logging,java.net.http,java.sql,jdk.charsets,jdk.crypto.ec'

$module = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$repo = (Resolve-Path (Join-Path $module '..')).Path
$target = Join-Path $module 'target'

function Invoke-Checked([string]$what, [scriptblock]$block) {
    & $block
    if ($LASTEXITCODE -ne 0) { throw "$what failed with exit code $LASTEXITCODE" }
}

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jpackage.exe'))) {
    throw 'JDK 25 with jpackage not found: pass -JavaHome or set JAVA_HOME'
}
$env:JAVA_HOME = $JavaHome

[xml]$pom = Get-Content (Join-Path $module 'pom.xml')
$version = $pom.project.parent.version
if ($version -notmatch '^\d+(\.\d+){0,2}$') { throw "jpackage needs a numeric version, got '$version'" }

# 1. Jars ------------------------------------------------------------------------------------------------------
if (-not $SkipMaven) {
    $mvn = Join-Path $repo 'mvnw.cmd'
    $mvnArgs = @('-B', '-f', (Join-Path $repo 'pom.xml'), '-pl', 'print-agent', '-am', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    Invoke-Checked 'Maven' { & $mvn @mvnArgs }
}
$jar = Join-Path $target "print-agent-$version.jar"
if (-not (Test-Path $jar)) { throw "$jar not found: build the module first" }

# 2. Stage: the main jar plus lib\ (referenced by the jar's manifest Class-Path) -------------------------------
$stage = Join-Path $target 'jpackage-input'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force (Join-Path $stage 'lib') | Out-Null
Copy-Item $jar $stage
Copy-Item (Join-Path $target 'lib\*.jar') (Join-Path $stage 'lib')

# 3. App image -------------------------------------------------------------------------------------------------
$windowsOut = Join-Path $target 'windows'
$imageRoot = Join-Path $windowsOut 'image'
if (Test-Path $windowsOut) { Remove-Item -Recurse -Force $windowsOut }
New-Item -ItemType Directory -Force $imageRoot | Out-Null
$jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
Invoke-Checked 'jpackage' {
    & $jpackage --type app-image `
        --name $AppName `
        --app-version $version `
        --vendor 'StandPOS' `
        --description 'StandPOS print agent' `
        --dest $imageRoot `
        --input $stage `
        --main-jar "print-agent-$version.jar" `
        --main-class $MainClass `
        --add-modules $Modules `
        --jlink-options '--strip-debug --no-header-files --no-man-pages' `
        --java-options '--enable-native-access=ALL-UNNAMED' `
        --java-options '-Xmx256m' `
        --win-console
}
$image = Join-Path $imageRoot $AppName
$exe = Join-Path $image "$AppName.exe"
Invoke-Checked 'Smoke test of the app image' { & $exe version }
Write-Host "App image: $image"

if ($SkipWinSW) {
    Write-Host 'Skipping WinSW and the zip (-SkipWinSW).'
    exit 0
}

# 4. WinSW -----------------------------------------------------------------------------------------------------
$winswDir = Join-Path $target 'winsw'
New-Item -ItemType Directory -Force $winswDir | Out-Null
$winsw = Join-Path $winswDir 'WinSW-x64.exe'
if ($WinSWPath) {
    Copy-Item $WinSWPath $winsw -Force
} elseif (-not (Test-Path $winsw)) {
    Write-Host "Downloading WinSW $WinSWVersion from $WinSWUrl"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $WinSWUrl -OutFile $winsw -UseBasicParsing
}
$actual = (Get-FileHash -Algorithm SHA256 $winsw).Hash.ToLowerInvariant()
if (-not $WinSWSha256) {
    throw "No pinned SHA-256 for WinSW yet. Downloaded file hash: $actual. Verify it, then set `$WinSWSha256 in build.ps1."
}
if ($actual -ne $WinSWSha256.ToLowerInvariant()) {
    Remove-Item $winsw -Force
    throw "WinSW checksum mismatch: expected $WinSWSha256, got $actual"
}
Copy-Item $winsw (Join-Path $image "$ServiceId.exe")

# WinSW reads <id>.xml next to <id>.exe; %BASE% is that folder, environment variables are expanded.
$xml = @"
<service>
  <id>$ServiceId</id>
  <name>$AppName</name>
  <description>Prints StandPOS receipts on this register's ESC/POS printer. Data and logs: %ProgramData%\StandPOS\PrintAgent</description>
  <executable>%BASE%\$AppName.exe</executable>
  <arguments>run</arguments>
  <env name="STANDPOS_AGENT_HOME" value="%ProgramData%\StandPOS\PrintAgent"/>
  <startmode>Automatic</startmode>
  <depend>Spooler</depend>
  <onfailure action="restart" delay="10 sec"/>
  <onfailure action="restart" delay="30 sec"/>
  <onfailure action="restart" delay="60 sec"/>
  <resetfailure>1 hour</resetfailure>
  <stoptimeout>15 sec</stoptimeout>
  <stopparentprocessfirst>true</stopparentprocessfirst>
  <logpath>%ProgramData%\StandPOS\PrintAgent\logs</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>5120</sizeThreshold>
    <keepFiles>3</keepFiles>
  </log>
</service>
"@
Set-Content -Path (Join-Path $image "$ServiceId.xml") -Value $xml -Encoding UTF8

# 5. Zip -------------------------------------------------------------------------------------------------------
$packageName = "StandPOS-PrintAgent-$version"
$package = Join-Path $windowsOut $packageName
New-Item -ItemType Directory -Force $package | Out-Null
Move-Item $image (Join-Path $package $AppName)
Copy-Item (Join-Path $PSScriptRoot 'install.ps1'), (Join-Path $PSScriptRoot 'uninstall.ps1') $package
$zip = Join-Path $target "$packageName-windows-x64.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path $package -DestinationPath $zip
Write-Host "Package: $zip"
