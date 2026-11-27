package dev.dshtabs

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties

/**
 * One authenticated SSH session, opened the one way this app opens them.
 *
 * There are two kinds of connection in this client — the Harness tab's session and a
 * terminal's — and they must be indistinguishable to the far side in the three things
 * that matter: **which host key is accepted**, which credential is offered, and what
 * keeps the socket alive. That is not a style preference. A terminal that verified
 * host keys differently, or that offered `ssh-rsa` where the Harness tab does not,
 * would be a second security model reachable from the same device record, and the
 * one an operator would reach for whenever a host key changed.
 *
 * So the setup lives here once and both call sites use it:
 *
 * - **Host key verification is ours, never JSch's.** `TrustOnFirstUse` pins the key
 *   from the device book and refuses a *changed* one; `StrictHostKeyChecking=yes` is
 *   set only because JSch's own `ask`/`yes` would consult a store this app does not
 *   keep, and `no` accepts a changed key silently. The trap in that switch name is
 *   written down in the README's "three rules a port gets wrong by default".
 * - **A password is used for both methods.** `setPassword` covers `password`, and
 *   `PasswordUserInfo` covers `keyboard-interactive`; a PAM-only host refuses a
 *   correct password without the second. See [PasswordUserInfo].
 * - **The keepalive interval is milliseconds.** JSch silently ignores
 *   `ServerAliveCountMax` unless `ServerAliveInterval` is set, and its unit is not
 *   OpenSSH's: a literal `20` would ask for twenty *milliseconds*. Twenty seconds by
 *   three misses is the 60 s the rest of this app assumes.
 * - **`timeout` is a socket timeout for the whole session, not a connect timeout.**
 *   The handshake is bounded by the `connect(...)` argument; the field is then
 *   cleared, because leaving a read timeout on an idle-by-design session disconnects
 *   a healthy connection.
 *
 * Nothing here is Android-specific, so it is also what an instrumented test can call.
 */
internal object Ssh {

	/**
	 * The handshake's deadline.
	 *
	 * The same number the Harness connect uses: a phone on a relayed path can take a
	 * few seconds to reach a host, and a machine that has not answered in twenty has
	 * not answered.
	 */
	const val CONNECT_TIMEOUT_MS = 20_000

	/**
	 * Open and authenticate one session.
	 *
	 * Blocking, and it must be called from a worker thread — every JSch call here is a
	 * socket operation. The caller owns the returned session and is responsible for
	 * `disconnect()`ing it.
	 *
	 * @param device the machine, with the address already resolved (`Addresses.resolve`).
	 * @param credential what to authenticate with.
	 * @param onHostKey receives a key learned on first use; the caller persists it.
	 * @return the connected session.
	 */
	fun open(device: Device, credential: Credential, onHostKey: (String) -> Unit = {}): Session {
		val jsch = JSch()
		if (credential is Credential.PrivateKey) {
			// The four-argument overload, the only one that takes a passphrase at all;
			// `pubkey` stays null because the key carries its own. See `Credential`.
			jsch.addIdentity("dsh-tabs", credential.pem, null, credential.passphrase?.toByteArray(Charsets.UTF_8))
		}
		return jsch.getSession(device.user, device.host, device.sshPort).apply {
			hostKeyRepository = TrustOnFirstUse(device.hostKey, onHostKey)
			setConfig(Properties().apply {
				put("StrictHostKeyChecking", "yes")
				put(
					"PreferredAuthentications",
					if (credential is Credential.Password) "password,keyboard-interactive,publickey" else "publickey"
				)
				put("ServerAliveInterval", "20000")
				put("ServerAliveCountMax", "3")
				System.setProperty("jsch.keepalive.message", "true")
			})
			if (credential is Credential.Password) {
				setPassword(credential.secret)
				setUserInfo(PasswordUserInfo(credential.secret))
			}
			connect(CONNECT_TIMEOUT_MS)
			timeout = 0
		}
	}
}
