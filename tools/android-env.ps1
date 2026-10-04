# Android toolchain paths, for whatever machine you are on.
#
#   . .\tools\android-env.ps1
#
# Dot-source it at the top of a session, or let the helper scripts in this folder do it
# for you. Nothing here is on the machine's PATH by default.
#
# **This file is generic on purpose.** It reads a local override first, so a machine
# with an unusual layout keeps its paths in `tools\android-env.local.ps1` — which is
# ignored by git — instead of editing a tracked file. That is also where the machine
# this was developed on keeps its own layout, because its JDK, Gradle and scrcpy are
# unpacked into a directory of their own rather than installed.

# A local override, when one exists, wins: it is sourced after the defaults below.
$localEnvScript = Join-Path $PSScriptRoot 'android-env.local.ps1'

# --- defaults -------------------------------------------------------------
# Deliberately "whatever this machine already has": an Android Studio install exports
# ANDROID_HOME, and a JDK is usually reachable through JAVA_HOME or the PATH.
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if (-not $env:ANDROID_HOME) {
	$guesses = @(
		(Join-Path $env:LOCALAPPDATA 'Android\Sdk')
		(Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
	)
	foreach ($guess in $guesses) {
		if (Test-Path $guess) { $env:ANDROID_HOME = $guess; $env:ANDROID_SDK_ROOT = $guess; break }
	}
}

function global:Test-AndroidEnv {
	<#
	.SYNOPSIS
		Report whether each piece of the toolchain is reachable and what version it is.
	#>
	$checks = [ordered]@{
		'java'       = @('java', '-version')
		'adb'        = @('adb', 'version')
		'sdkmanager' = @('sdkmanager.bat', '--version')
		'aapt2'      = @('aapt2.exe', 'version')
		'gradle'     = @('gradle.bat', '--version')
		'scrcpy'     = @('scrcpy.exe', '--version')
	}
	foreach ($name in $checks.Keys) {
		$exe, $arg = $checks[$name]
		$cmd = Get-Command $exe -ErrorAction SilentlyContinue
		if (-not $cmd) { Write-Host ("  {0,-12} MISSING" -f $name); continue }
		# Take the first line that carries information. `gradle --version` opens
		# with a blank line and a ruler made of dashes, so neither [0] nor
		# "first non-blank" names the version.
		$lines = @(& $cmd.Source $arg 2>&1 | ForEach-Object { "$_" })
		$out = $lines | Where-Object { $_ -match '\d' } | Select-Object -First 1
		if (-not $out) { $out = $lines | Where-Object { $_.Trim() } | Select-Object -First 1 }
		Write-Host ("  {0,-12} {1}" -f $name, $out)
	}
	Write-Host ""
	Write-Host "  ANDROID_HOME = $env:ANDROID_HOME"
	Write-Host "  JAVA_HOME    = $env:JAVA_HOME"
}

function global:Get-AndroidDevice {
	<#
	.SYNOPSIS
		Pick a device serial, preferring an emulator when several are attached.

	.DESCRIPTION
		With only one device attached this is unambiguous. With two, "the first
		one adb lists" is a coin toss between the tablet and the emulator, and
		choosing wrong installs onto or photographs the wrong target without
		saying so. The emulator wins the tie because it is the one that cannot
		be locked, which is what makes it useful for screenshots.
	#>
	$lines = & adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
	$ready = @($lines | Where-Object { $_ -match '\sdevice\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
	if ($ready.Count -eq 0) {
		Write-Warning "No device is in the 'device' state. Attach one and check 'adb devices'."
		if ($lines) { $lines | ForEach-Object { Write-Host "    $_" } }
		return $null
	}
	if ($ready.Count -eq 1) { return $ready[0] }

	$emu = $ready | Where-Object { $_ -like 'emulator-*' }
	if ($emu) {
		Write-Host "  ($($ready.Count) devices attached; using emulator $($emu[0]) of: $($ready -join ', '))"
		return $emu[0]
	}
	Write-Warning "More than one device attached and none is an emulator; take the first. Pass -Serial to choose."
	return $ready[0]
}

# --- local override -------------------------------------------------------
if (Test-Path $localEnvScript) { . $localEnvScript }
