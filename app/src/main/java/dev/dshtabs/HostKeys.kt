package dev.dshtabs

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.util.Base64

/**
 * Trust on first use, then pin.
 *
 * **JSch's own `StrictHostKeyChecking` cannot express this, and that is the whole
 * reason this file exists.** With `StrictHostKeyChecking=no` — the setting that
 * looks like OpenSSH's `accept-new` — JSch accepts a *changed* host key silently:
 * in `Session.doCheckHostKey` the changed-key branch is only taken when the value
 * is `ask` or `yes`. A machine that has been rebuilt, or an attacker in the middle,
 * would therefore be accepted without a word after the first connection. OpenSSH's
 * `accept-new` means "accept what I have not seen, refuse what has changed", and
 * that is what this implements.
 *
 * The pin lives in the device record, so it travels with the machine it belongs to
 * and a reinstall of the app forgets it — which is correct: a reinstalled app has
 * no memory of what it trusted, and re-establishing trust is a decision for the
 * operator, not a silent default.
 */
class TrustOnFirstUse(
	/** The pinned key, base64 of the SSH wire format, or null on a first connect. */
	private val pinned: String?,
	/** Called once, on the first successful connection, with the key to remember. */
	private val onFirstUse: (String) -> Unit
) : HostKeyRepository {

	/** Thrown when the key is not the one that was pinned. */
	class Changed(host: String, keyType: String) : RuntimeException(
		"the host key for $host has changed (now $keyType). " +
			"That is either a rebuilt machine or something in the middle. " +
			"Remove the machine and add it again to trust the new key."
	)

	override fun check(host: String, key: ByteArray?): Int {
		if (key == null) return HostKeyRepository.NOT_INCLUDED
		val presented = Base64.getEncoder().encodeToString(key)
		if (pinned == null) {
			onFirstUse(presented)
			return HostKeyRepository.OK
		}
		if (presented == pinned) return HostKeyRepository.OK
		// The key type is the first length-prefixed field of the wire format: a
		// four-byte big-endian length, then the algorithm name in ASCII. Reading it
		// here avoids needing a JSch object, which `check` is not given.
		throw Changed(host, wireTypeName(key))
	}

	/** The algorithm name out of an SSH public key blob, or `?` if it is malformed. */
	private fun wireTypeName(key: ByteArray): String {
		if (key.size < 5) return "?"
		val length = ((key[0].toInt() and 0xFF) shl 24) or ((key[1].toInt() and 0xFF) shl 16) or
			((key[2].toInt() and 0xFF) shl 8) or (key[3].toInt() and 0xFF)
		if (length <= 0 || length > key.size - 4) return "?"
		return String(key, 4, length, Charsets.US_ASCII)
	}

	/**
	 * Never consulted: [check] answers every case rather than deferring to a store.
	 *
	 * The repository is built per device and carries exactly one key, so there is no
	 * "the rest of the known hosts" to enumerate.
	 */
	override fun getKnownHostsRepositoryID(): String = "dsh-tabs"

	override fun getHostKey(): Array<HostKey> = emptyArray()

	override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

	/**
	 * Refuse to let anything add a key behind [check]'s back.
	 *
	 * JSch calls this for a key the user has been asked about. This app never asks —
	 * there is no dialog that can be answered safely while a connection is being
	 * established — so reaching here means a path this class does not control, and
	 * silence would defeat the pinning.
	 */
	override fun add(hostkey: HostKey?, userinfo: UserInfo?) {
		/* deliberately not implemented: see the KDoc */
	}

	override fun remove(host: String?, type: String?) {
		/* the pin belongs to the device record, not to a key store */
	}

	override fun remove(host: String?, type: String?, key: ByteArray?) {
		/* the pin belongs to the device record, not to a key store */
	}
}
