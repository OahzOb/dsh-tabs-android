#Requires -Version 5.1
<#
.SYNOPSIS
    Capture what the app currently looks like on the attached device.

.DESCRIPTION
    The point of this script is that a picture is reviewable and a build log is
    not. It launches the app, lets it settle, then pulls a real framebuffer
    screenshot off the device into .shots\ — which is the folder the harness can
    read back and show.

.PARAMETER AppId
    Package to launch. Defaults to dev.dshtabs.

.PARAMETER Activity
    Activity to launch. Defaults to .MainActivity, resolved against AppId.

.PARAMETER Label
    Name for the shot, so a series reads in order. Defaults to a counter.

.PARAMETER NoLaunch
    Do not launch the app; capture whatever is on screen right now. Use this to
    photograph something the app reached on its own, or a system dialog.

.PARAMETER SettleMs
    Milliseconds to wait after launching before capturing. Layout and any first
    network round trip need time to finish.

.PARAMETER Composite
    Also scale the shot down to a shareable width and save it beside the original.

.EXAMPLE
    .\tools\shot.ps1 -Label 01-launch
    .\tools\shot.ps1 -Label 02-settings -NoLaunch
#>
[CmdletBinding()]
param(
    [string]$AppId = 'dev.dshtabs',
    [string]$Activity = '.MainActivity',
    [string]$Label,
    [switch]$NoLaunch,
    [int]$SettleMs = 2500,
    [int]$CompositeWidth = 900
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-env.ps1')

$serial = Get-AndroidDevice
if (-not $serial) { exit 1 }

$repo = Split-Path $PSScriptRoot -Parent
$shotDir = Join-Path $repo '.shots'
New-Item -ItemType Directory -Force -Path $shotDir | Out-Null

# Auto-number when no label is given, so a bare run still produces an ordered series.
if (-not $Label) {
    $existing = Get-ChildItem $shotDir -Filter '*.png' -ErrorAction SilentlyContinue
    $Label = '{0:D2}-shot' -f ($existing.Count + 1)
}

$png = Join-Path $shotDir "$Label.png"

# A sleeping or locked device photographs as pure black, and the failure is
# silent: screencap succeeds and returns a perfectly valid all-black PNG. So
# wake the screen first and report what state it was found in.
function Wake-Screen {
    param([string]$Serial)

    $sleeping = (& adb -s $Serial shell dumpsys power 2>&1 |
        Select-String -Pattern 'mWakefulness=(\w+)' |
        Select-Object -First 1) -match 'mWakefulness=(\w+)'
    $state = if ($sleeping) { $Matches[1] } else { 'Unknown' }

    if ($state -ne 'Awake') {
        Write-Host "Screen was $state; waking it ..."
        & adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
        Start-Sleep -Milliseconds 900
    }

    $kg = & adb -s $Serial shell dumpsys window 2>&1 |
        Select-String -Pattern 'isKeyguardShowing=(\w+)' | Select-Object -First 1
    if ($kg -match 'isKeyguardShowing=true') {
        Write-Host "Keyguard is up; dismissing ..."
        # Works when the device has no secure lock. A PIN or pattern cannot be
        # dismissed from adb, and the shot below will be of the lock screen.
        & adb -s $Serial shell wm dismiss-keyguard | Out-Null
        Start-Sleep -Milliseconds 1200
    }

    # Keep the panel on for as long as USB is attached, so a later shot in the
    # same session does not have to wake it again.
    & adb -s $Serial shell svc power stayon usb | Out-Null
}

Wake-Screen -Serial $serial

if (-not $NoLaunch) {
    Write-Host "Launching $AppId/$Activity on $serial ..."
    # -W waits for the launch to be dispatched, which makes the settle below meaningful.
    & adb -s $serial shell am start -W -n "$AppId/$Activity" | Out-Null
    Start-Sleep -Milliseconds $SettleMs
} else {
    Write-Host "Capturing the current screen on $serial (no launch) ..."
    Start-Sleep -Milliseconds 400
}

# screencap writes to stdout; redirecting binary through PowerShell mangles it,
# so let adb write the file itself and then pull it.
$remote = '/sdcard/dsh-shot.png'
& adb -s $serial shell screencap -p $remote
if ($LASTEXITCODE -ne 0) { throw "screencap failed on the device" }

& adb -s $serial pull $remote $png | Out-Null
& adb -s $serial shell rm -f $remote | Out-Null

if (-not (Test-Path $png)) { throw "Pull produced no file at $png" }

$size = (Get-Item $png).Length
if ($size -lt 5000) { Write-Warning "Shot is only $size bytes — the screen may be black or off." }

Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($png)
$w, $h = $img.Width, $img.Height

# A black framebuffer is the failure mode this script has to catch, because
# screencap reports success either way. Sample a grid rather than every pixel:
# 60x60 points is enough to tell "dark UI" from "nothing was drawn".
$bmp = New-Object System.Drawing.Bitmap($img)
$img.Dispose()
$sum = 0.0
$n = 0
$stepX = [Math]::Max(1, [int]($w / 60))
$stepY = [Math]::Max(1, [int]($h / 60))
for ($y = 0; $y -lt $h; $y += $stepY) {
    for ($x = 0; $x -lt $w; $x += $stepX) {
        $p = $bmp.GetPixel($x, $y)
        $sum += (0.299 * $p.R + 0.587 * $p.G + 0.114 * $p.B)
        $n++
    }
}
$bmp.Dispose()
$meanBrightness = if ($n -gt 0) { $sum / $n } else { 0 }

$composite = Join-Path $shotDir "$Label.small.png"
if ($CompositeWidth -gt 0 -and $w -gt $CompositeWidth) {
    $scale = $CompositeWidth / $w
    $nw = [int]($w * $scale)
    $nh = [int]($h * $scale)
    $src = [System.Drawing.Image]::FromFile($png)
    $bmp = New-Object System.Drawing.Bitmap($nw, $nh)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($src, 0, 0, $nw, $nh)
    $g.Dispose(); $src.Dispose()
    $bmp.Save($composite, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

Write-Host ""
Write-Host "Shot    : $png"
# Parenthesise the -f expression: a bare `Write-Host "..." -f $x` binds $x to
# -ForegroundColor instead, and a number is not a ConsoleColor.
Write-Host ("Size    : {0}x{1}  ({2:N0} KB)" -f $w, $h, ($size / 1KB))
Write-Host ("Mean    : {0:N1}/255 brightness" -f $meanBrightness)
if ($meanBrightness -lt 2) {
    Write-Warning "The shot is essentially black. The device is probably still asleep or locked, or the app drew nothing."
}
if (Test-Path $composite) { Write-Host ("Preview : {0}  ({1}px wide)" -f $composite, $CompositeWidth) }
