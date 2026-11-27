package dev.dshtabs

import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo

/**
 * Answers JSch's interactive prompts with the password the operator typed.
 *
 * **Why `setPassword` alone is not enough.** JSch's `password` method takes the
 * secret from `Session.setPassword`, but the `keyboard-interactive` method does not:
 * `UserAuthKeyboardInteractive` calls `session.getUserInfo()` and reads the answers
 * from there, and with no `UserInfo` it fails with `USERAUTH fail` even though the
 * password is correct. A Debian or Ubuntu host whose `sshd_config` says
 * `KbdInteractiveAuthentication yes` and `PasswordAuthentication no` — a very common
 * PAM arrangement — offers *only* keyboard-interactive, so a client that handles just
 * `password` is refused with a password that is perfectly good. The app lists both
 * methods in `PreferredAuthentications`, so both have to be answerable.
 *
 * The same object also answers `promptPassword`, which JSch asks on the password
 * path when it wants `getPassword()` rather than the value it was handed.
 *
 * **The secret stays in memory.** It is held by this object for the life of one
 * session and is never written anywhere; `showMessage` deliberately does nothing,
 * because JSch passes far-side banner text through it and a dialog would be a second
 * surface the operator did not ask for.
 *
 * @param secret the password, as typed.
 */
internal class PasswordUserInfo(private val secret: String) : UserInfo, UIKeyboardInteractive {

	override fun getPassword(): String = secret

	/** No passphrase: this object is only ever used for a password credential. */
	override fun getPassphrase(): String? = null

	override fun promptPassword(message: String?): Boolean = true

	override fun promptPassphrase(message: String?): Boolean = false

	/**
	 * Refuse host-key questions.
	 *
	 * Host keys are decided by `TrustOnFirstUse`, never by a yes/no prompt: answering
	 * `true` here would accept a *changed* key, which is the one thing the app's
	 * repository exists to refuse.
	 */
	override fun promptYesNo(message: String?): Boolean = false

	override fun showMessage(message: String?) = Unit

	/**
	 * Answer every keyboard-interactive prompt with the one password.
	 *
	 * The number of answers is the number of prompts JSch was given. A server that
	 * asks more than one question — a second factor, an expired-password change — is
	 * not something a password can satisfy, and JSch refuses whatever does not match;
	 * repeating the secret is the honest attempt and fails the way it should.
	 */
	override fun promptKeyboardInteractive(
		destination: String?,
		name: String?,
		instruction: String?,
		prompt: Array<out String>?,
		echo: BooleanArray?
	): Array<String> = Array(prompt?.size ?: 1) { secret }
}

/**
 * Whether a JSch failure is a refused credential rather than a broken path.
 *
 * JSch reports both an authentication failure and a rejected key as `Auth fail`;
 * `Permission denied` reaches it when the far side's `sshd` says so before
 * disconnecting. A timeout or a host-key mismatch must not be mistaken for either —
 * offering a password after those would ask the operator to retype a secret that was
 * never sent.
 *
 * @param message the error text from a failed connect, or null.
 * @returns true when the credential was refused.
 */
internal fun isAuthFailure(message: String?): Boolean {
	val text = message.orEmpty()
	return text.contains("auth fail", ignoreCase = true) || text.contains("permission denied", ignoreCase = true)
}
