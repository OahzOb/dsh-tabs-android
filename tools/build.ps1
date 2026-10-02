#Requires -Version 5.1
<#
.SYNOPSIS
    Build the APK, and optionally install it on the attached device and show it.

.DESCRIPTION
    One command for the whole loop: compile, push, launch, capture. Every step is
    skippable so the same script serves a compile check and a full visual pass.

.PARAMETER Variant
    debug (default) or release.

.PARAMETER Install
    Install the built APK on the attached device before anything else.

.PARAMETER Shot
    Capture a screenshot after installing. Implies -Install.

.PARAMETER Label
    Label for the screenshot, passed through to shot.ps1.

.PARAMETER Offline
    Resolve dependencies from the Gradle cache only. Faster and works without a
    network, which matters because the machine reaches Maven Central through a
    local proxy.

.PARAMETER Clean
    Run clean first. Rarely what you want — it throws away the incremental state
    that makes this script take seconds.

.EXAMPLE
    .\tools\build.ps1
    .\tools\build.ps1 -Install
    .\tools\build.ps1 -Install -Shot -Label 03-after-change
#>
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug',
    [switch]$Install,
    [switch]$Shot,
    [string]$Label,
    [switch]$Offline,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-env.ps1')

if ($Shot) { $Install = $true }

$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    $gradleArgs = @()

    if ($Clean) { $gradleArgs += 'clean' }

    $task = if ($Variant -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
    $gradleArgs += $task

    if ($Offline) { $gradleArgs += '--offline' }
    $gradleArgs += @('--console=plain')

    Write-Host "gradlew $($gradleArgs -join ' ')" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # The wrapper, not the global gradle: gradle-wrapper.properties pins the
    # distribution next to the project, and on this machine it resolves to a
    # local zip so the build needs no network at all.
    & (Join-Path $repo 'gradlew.bat') @gradleArgs
    $code = $LASTEXITCODE
    $sw.Stop()

    if ($code -ne 0) {
        Write-Host ""
        Write-Host "BUILD FAILED (exit $code) after $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Red
        exit $code
    }
    Write-Host ""
    Write-Host "BUILD OK in $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Green

    $apk = Join-Path $repo "app\build\outputs\apk\$Variant\app-$Variant.apk"
    if (-not (Test-Path $apk)) { throw "Expected APK is missing: $apk" }
    Write-Host ("APK     : {0}  ({1:N1} MB)" -f $apk, ((Get-Item $apk).Length / 1MB))

    if (-not $Install) { return }

    $serial = Get-AndroidDevice
    if (-not $serial) { exit 1 }

    Write-Host "Installing on $serial ..."
    # -r replaces in place. The app keeps its data, which is the difference
    # between "test the change" and "start over from an empty device book".
    & adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

    if ($Shot) {
        Write-Host ""
        & (Join-Path $PSScriptRoot 'shot.ps1') -AppId 'dev.dshtabs' -Label $Label
    }
}
finally {
    Pop-Location
}
