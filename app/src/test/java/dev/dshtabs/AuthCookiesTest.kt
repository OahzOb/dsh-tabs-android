package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cookie-name arithmetic behind the shared-jar cleanup.
 *
 * The names are the Harness's own: `cookieName` in `dsh-client-connection` builds
 * `dsh-auth-` + base64url(SHA-256(authority)). The vector below was produced by that
 * function — not by this implementation — on 2026-11-23, so it fails if either side
 * changes its encoding. That matters more than it looks: a name this app computes
 * differently deletes nothing, silently, and the jar starts growing again while every
 * test here still passes. This one does not.
 */
class AuthCookiesTest {

	@Test
	fun `a name is the one the server derives for the same authority`() {
		assertEquals(
			"dsh-auth-jBcceMsl_ThCzi7BypM647S3nL7AqSOLn5GXdPICssw",
			AuthCookies.nameFor("127.0.0.1:49731")
		)
	}

	@Test
	fun `another authority is another name`() {
		assertNotEquals(
			AuthCookies.nameFor("127.0.0.1:49731"),
			AuthCookies.nameFor("127.0.0.1:49732")
		)
	}

	@Test
	fun `a header is split into names, and the values are left alone`() {
		assertEquals(
			listOf("dsh-auth-a", "other"),
			AuthCookies.names("dsh-auth-a=v1.abc==; other=v2;")
		)
	}

	@Test
	fun `an absent or empty header has no names`() {
		assertEquals(emptyList<String>(), AuthCookies.names(null))
		assertEquals(emptyList<String>(), AuthCookies.names(""))
		assertEquals(emptyList<String>(), AuthCookies.names(" ; ;; "))
	}

	@Test
	fun `only names whose authority is gone are dead`() {
		val live = AuthCookies.nameFor("127.0.0.1:1111")
		val dead = AuthCookies.nameFor("127.0.0.1:2222")
		assertEquals(
			listOf(dead),
			AuthCookies.deadNames("$live=v1; $dead=v2", listOf("127.0.0.1:1111"))
		)
	}

	@Test
	fun `a cookie this client did not mint is never deleted`() {
		val foreign = "sessionid=abc"
		val dead = AuthCookies.nameFor("127.0.0.1:9")
		assertEquals(
			listOf(dead),
			AuthCookies.deadNames("$foreign; $dead=v", emptyList())
		)
	}

	@Test
	fun `a live authority keeps its cookie and only the others go`() {
		val one = AuthCookies.nameFor("127.0.0.1:1000")
		val two = AuthCookies.nameFor("127.0.0.1:2000")
		assertEquals(
			listOf(two),
			AuthCookies.deadNames("$one=a; $two=b", listOf("127.0.0.1:1000"))
		)
	}

	@Test
	fun `the server's prefix is what marks a cookie as this client's to delete`() {
		assertTrue(AuthCookies.PREFIX == "dsh-auth-")
	}
}
