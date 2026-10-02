# Add an Android device's SSH key to THIS machine's sshd, so dsh-tabs on it can log in
# without a password.
#
#     Start-Process powershell -Verb RunAs -ArgumentList '-ExecutionPolicy Bypass -File "<path to this file>"'
#
# The app has **one key per device**, so a second Android device needs its own public
# key added here too. Pass it with -Key; with no argument the phone's key is added.
#
# Why it needs elevation, and why the obvious file is the wrong one:
#
# This account is in the Administrators group, and the shipped `sshd_config` ends with
#
#     Match Group administrators
#         AuthorizedKeysFile __PROGRAMDATA__/ssh/administrators_authorized_keys
#
# which *overrides* the earlier `.ssh/authorized_keys` for exactly those accounts. So
# putting the key in `~\.ssh\authorized_keys` would look right and never be read, and
# sshd would go on refusing the device. `%ProgramData%\ssh` is writable only by an
# elevated process, which is why this cannot be run from a normal prompt.
#
# The ACL matters as much as the content: sshd ignores this file unless its permissions
# are restricted to SYSTEM and Administrators, and it does that silently — the
# connection is simply refused with no explanation in the client.
#
# **Every step is logged**, because the first version of this script died in an elevated
# window that closed before it could be read, and the only symptom was that nothing
# changed. The log says which file was written, which lines it holds, and what the ACL
# is afterwards, so a failure can be diagnosed from a normal prompt.

# The key to add. Pass one with -Key, or leave it off to add the phone's key.
param(
	[string]$Key = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDcWWe8Q3wW0QlILw9S5r7aOn7y8KDjjBKw2xMujqvZX dsh-tabs-android'
)

$log = Join-Path $env:TEMP 'dsh-add-phone-key.log'
Start-Transcript -Path $log -Force | Out-Null

function Say([string]$message) {
	Write-Host $message
}

try {
	Say "running as : $([Security.Principal.WindowsIdentity]::GetCurrent().Name)"
	Say "elevated   : $((New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))"
	Say "powershell : $($PSVersionTable.PSVersion)"
	Say "log        : $log"
	Say ''

	$key = $Key
	$dir = Join-Path $env:ProgramData 'ssh'
	$target = Join-Path $dir 'administrators_authorized_keys'

	# Confirm this is the file sshd will actually read, rather than assuming it.
	$config = Join-Path $dir 'sshd_config'
	$usesAdminFile = $false
	if (Test-Path $config) {
		$usesAdminFile = [bool](Select-String -Path $config -Pattern 'administrators_authorized_keys' -Quiet)
	}
	Say "sshd_config points at administrators_authorized_keys: $usesAdminFile"
	Say "target file: $target"
	Say ''

	if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

	# Idempotent: running this twice must not leave two copies, which is harmless to
	# sshd but confusing to whoever reads the file next.
	$existing = @()
	if (Test-Path $target) {
		$existing = @(Get-Content $target | Where-Object { $_.Trim() -ne '' })
	}
	Say "existing entries: $($existing.Count)"

	$already = @($existing | Where-Object { $_.Trim() -eq $key })
	if ($already.Count -gt 0) {
		Say 'that key is already present; nothing to add'
	} else {
		$existing += $key
		# UTF-8 without a BOM. A BOM at the top of an authorized_keys file makes the
		# first entry unparseable, and it is invisible in most editors.
		$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
		[System.IO.File]::WriteAllLines($target, $existing, $utf8NoBom)
		Say 'added the key'
	}

	Say ''
	Say 'contents:'
	$i = 0
	foreach ($line in Get-Content $target) {
		$i++
		$short = if ($line.Length -gt 72) { $line.Substring(0, 72) + '...' } else { $line }
		Say "  ${i}: $short"
	}

	# Restrict the ACL, which sshd enforces. Without this the file is present, correct,
	# and ignored. Each call is independent: a principal that is not present is not an
	# error worth stopping for.
	Say ''
	Say 'locking down the ACL...'
	& icacls $target /inheritance:r | Out-Null
	& icacls $target /grant 'SYSTEM:F' | Out-Null
	& icacls $target /grant 'BUILTIN\Administrators:F' | Out-Null
	foreach ($principal in @('NT AUTHORITY\Authenticated Users', 'BUILTIN\Users', "$env:USERDOMAIN\$env:USERNAME")) {
		$null = & icacls $target /remove $principal 2>&1
		Say "  removed $principal (or it was not there)"
	}

	Say ''
	Say 'ACL now (sshd requires SYSTEM and Administrators only):'
	& icacls $target | ForEach-Object { Say "  $_" }

	Say ''
	Say 'restarting sshd...'
	Restart-Service sshd
	$service = Get-Service sshd
	Say "  sshd: $($service.Status) / $($service.StartType)"

	Say ''
	Say 'DONE. Back to the app, tap the machine and it should not ask for a password.'
} catch {
	Say ''
	Say "FAILED: $($_.Exception.GetType().Name): $($_.Exception.Message)"
	Say $_.ScriptStackTrace
} finally {
	Stop-Transcript | Out-Null
	Write-Host ''
	Write-Host "A copy of this is in $log"
	Write-Host 'Press Enter to close...'
	# The window stays open on purpose: this runs elevated, and an elevated window that
	# vanishes takes its error message with it.
	$null = Read-Host
}
