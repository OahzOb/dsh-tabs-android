package dev.dshtabs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session that is opened but never delivered must still be stoppable.
 *
 * `RemoteSession.open` runs the connect inside `withContext(Dispatchers.IO)`. A
 * cancellation is observed at that boundary *after* the block has returned
 * `SessionStart.Ok`, so the value is discarded without the block's own
 * `catch (CancellationException)` ever running — and a caller that only holds the
 * returned result has nothing to stop. The fix is structural: the session and
 * channel are registered as they are created, and an outer `finally` releases them
 * when the result was not delivered.
 *
 * Nothing in the JVM test source set can open a JSch session, and Robolectric is
 * not a dependency here, so this reads the file as text — the same compromise
 * [TabClickTest] makes, with the same warning: it proves the shape, not the
 * behaviour. What it buys is that the registration cannot move back behind the
 * return, or the outer `finally` disappear, without deleting a test whose failure
 * message says why it must not.
 */
class SessionOwnershipTest {

	private fun source(): String {
		val candidates = listOf(
			java.io.File("src/main/java/dev/dshtabs/RemoteSession.kt"),
			java.io.File("app/src/main/java/dev/dshtabs/RemoteSession.kt")
		)
		for (candidate in candidates) {
			if (candidate.isFile) return candidate.readText()
		}
		throw AssertionError(
			"no RemoteSession.kt to read: neither ${candidates.first().absolutePath} nor " +
				"${candidates.last().absolutePath} exists. Gradle runs this task from the " +
				"module directory; run it from one of those instead of passing."
		)
	}

	@Test
	fun `an undelivered session is released by open's outer finally`() {
		val text = source()
		val open = text.indexOf("suspend fun open(")
		val internal = text.indexOf("private suspend fun openInternal")
		assertTrue("RemoteSession.open is missing", open >= 0)
		assertTrue("the connect body is no longer in openInternal", internal > open)
		val wrapper = text.substring(open, internal)
		assertTrue(
			"open no longer has an outer finally, so a result discarded at the withContext " +
				"boundary leaves the SSH session, the forward and the far side's dsh running",
			wrapper.contains("finally")
		)
		assertTrue(
			"open does not release the resources when the result was not delivered",
			wrapper.contains("opened.release()")
		)
	}

	@Test
	fun `each resource is registered before the result can be returned`() {
		val text = source()
		val body = text.indexOf("private suspend fun openInternal")
		val result = text.indexOf("SessionStart.Ok(handle)")
		assertTrue("the connect body is missing", body >= 0)
		assertTrue("the success value is no longer built from `handle`", result > body)
		for (registration in listOf("opened.session(connected)", "opened.channel(exec)", "opened.remote(handle)")) {
			val at = text.indexOf(registration)
			assertTrue(
				"`$registration` is missing. A resource registered only after the result is " +
					"handed over cannot be stopped when the result is discarded.",
				at > body
			)
			assertTrue(
				"`$registration` comes after the success value, so a cancellation at the " +
					"withContext boundary can still leave it unregistered",
				at < result
			)
		}
	}

	@Test
	fun `the platform probe's read has a deadline`() {
		val text = source()
		assertTrue(
			"runProbe reads the channel without a deadline; a host that accepts the exec " +
				"channel and then goes quiet holds the whole connect open",
			text.contains("future.get(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)")
		)
	}
}
