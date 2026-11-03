package dev.dshtabs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A window may be destroyed and rebuilt while its connections stay up.
 *
 * This is the fix for the reported split screen disconnect, and it is a lifecycle
 * change, so the parts that matter are the ones a JVM test cannot execute: `onDestroy`
 * choosing between "stop everything" and "hand it over", and `onCreate` adopting what
 * it finds. Robolectric is not a dependency here, so this reads `MainActivity.kt` as
 * text — the same compromise [TabClickTest] and [SessionOwnershipTest] make. It proves
 * the shape of the handover, not that Android delivers the callbacks in the order the
 * shape assumes; that was checked on the emulator and is recorded in the README.
 *
 * What it buys is that the two halves cannot drift apart silently: an `onDestroy` that
 * stops a session it was supposed to hand over, an `onCreate` that forgets to adopt, or
 * a render that compares the view against the token URL after an adopted session has
 * spent that token — each of those turns the fix back into the bug, and none of them
 * fails to compile.
 */
class SessionSurvivalTest {

	private fun source(): String {
		val candidates = listOf(
			java.io.File("src/main/java/dev/dshtabs/MainActivity.kt"),
			java.io.File("app/src/main/java/dev/dshtabs/MainActivity.kt")
		)
		for (candidate in candidates) {
			if (candidate.isFile) return candidate.readText()
		}
		throw AssertionError(
			"no MainActivity.kt to read: neither ${candidates.first().absolutePath} nor " +
				"${candidates.last().absolutePath} exists. Gradle runs this task from the " +
				"module directory; run it from one of those instead of passing."
		)
	}

	/** The body of one function, by matching its braces from the opening brace. */
	private fun body(text: String, signature: String): String {
		val start = text.indexOf(signature)
		assertTrue("`$signature` is missing from MainActivity", start >= 0)
		val open = text.indexOf('{', start)
		assertTrue("`$signature` has no body", open >= 0)
		var depth = 0
		var index = open
		while (index < text.length) {
			when (text[index]) {
				'{' -> depth++
				'}' -> {
					depth--
					if (depth == 0) return text.substring(open, index + 1)
				}
			}
			index++
		}
		throw AssertionError("`$signature` is never closed")
	}

	@Test
	fun `a recreating destroy hands the sessions over instead of stopping them`() {
		val destroy = body(source(), "override fun onDestroy()")
		assertTrue(
			"onDestroy no longer branches on `isFinishing`, so it cannot tell a Back " +
				"out of the app from a window being recreated for split screen",
			destroy.contains("isFinishing")
		)
		assertTrue(
			"onDestroy does not adopt into `Sessions.running`, so a recreated window " +
				"starts from the book with every tab IDLE — which is the split-screen " +
				"disconnect, with a CONNECT button in front of a machine whose `dsh web` " +
				"was just reaped",
			destroy.contains("Sessions.running.adopt(")
		)
	}

	@Test
	fun `a new window adopts what the registry holds and draws it as running`() {
		val adopt = body(source(), "private fun adoptRunningSessions()")
		assertTrue(
			"adoptRunningSessions never asks the registry, so there is nothing to adopt",
			adopt.contains("Sessions.running.snapshot()")
		)
		assertTrue(
			"an adopted session is not put on its tab",
			adopt.contains("tab.session = session")
		)
		assertTrue(
			"an adopted session does not make its tab RUNNING, so the panel would offer " +
				"CONNECT while the connection is up and a second `dsh web` would be started",
			adopt.contains("tab.state = State.RUNNING")
		)
	}

	@Test
	fun `the page is reconciled against the page, not the spent token URL`() {
		val render = body(source(), "private fun renderContent()")
		assertTrue(
			"renderContent no longer reads `pageUrl`, so an adopted session — whose token " +
				"URL has already been spent — would be rebuilt on every render, or never " +
				"loaded from the origin the cookie belongs to",
			render.contains("pageUrl")
		)
		assertTrue(
			"the view reconciliation compares something other than the page it built",
			render.contains("current.pageUrl == entry.url") &&
				render.contains("it.url == pageUrl")
		)
	}
}
