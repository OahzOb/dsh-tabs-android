package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The password half of a JSch login.
 *
 * `setPassword` covers the `password` method and nothing else: the
 * `keyboard-interactive` method reads its answers from `UserInfo`, so a PAM-only
 * host — the common Debian/Ubuntu arrangement — refuses a correct password with
 * `Auth fail` unless `PasswordUserInfo` is installed. These checks pin both the
 * answers and the narrow definition of "the credential was refused" that decides
 * whether a password is forgotten or offered.
 */
class AuthTest {

	@Test
	fun `keyboard-interactive is answered with the password, and nothing else is accepted`() {
		val user = PasswordUserInfo("secret")
		assertEquals("secret", user.getPassword())
		// A password credential has no passphrase, and saying otherwise would make JSch
		// ask for one it can never get.
		assertNull(user.getPassphrase())
		assertTrue(user.promptPassword("Password:"))
		assertFalse(user.promptPassphrase("Passphrase:"))
		// A yes/no prompt is a host-key question. Answering true would accept a changed
		// key, which is the one thing `TrustOnFirstUse` exists to refuse.
		assertFalse(user.promptYesNo("Continue connecting?"))

		val one = user.promptKeyboardInteractive("box", "deploy", "", arrayOf("Password: "), booleanArrayOf(false))
		assertEquals(listOf("secret"), one.toList())
		// One answer per question: JSch hands the array straight back to the server.
		val two = user.promptKeyboardInteractive("box", "deploy", "", arrayOf("a", "b"), booleanArrayOf(false, false))
		assertEquals(listOf("secret", "secret"), two.toList())
	}

	@Test
	fun `only a refused credential counts as an authentication failure`() {
		assertTrue(isAuthFailure("Auth fail"))
		assertTrue(isAuthFailure("com.jcraft.jsch.JSchException: Auth fail"))
		assertTrue(isAuthFailure("Permission denied (publickey,password)."))
		// The cases where offering a password would ask the operator to retype a secret
		// that was never sent.
		assertFalse(isAuthFailure("connection is closed by foreign host"))
		assertFalse(isAuthFailure("Host key verification failed"))
		assertFalse(isAuthFailure("timeout: socket is not established"))
		assertFalse(isAuthFailure(null))
	}
}
