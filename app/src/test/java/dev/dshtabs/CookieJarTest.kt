package dev.dshtabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of the shared-jar cleanup, read as text.
 *
 * [AuthCookiesTest] proves the arithmetic; this proves the places that must use it.
 * Robolectric is not a dependency here, so this reads `MainActivity.kt` the same way
 * [SessionSurvivalTest] and [TabClickTest] do — it proves the shape of the call, not
 * that Android's `CookieManager` honours the deletion, which the README records as a
 * device measurement.
 *
 * The failures it guards all compile: a connect that starts the new session before
 * removing the dead cookies, a 431 handler that goes straight back to emptying the
 * shared jar (signing the other tabs out), or a pre-load check that reintroduces its
 * own size literal and drifts from the handler's.
 */
class CookieJarTest {

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
	fun `a connect drops the dead cookies before it starts the new session`() {
		val start = body(source(), "private fun startSession(")
		val stop = start.indexOf("tab.stop()")
		val prune = start.indexOf("dropDeadAuthCookies()")
		assertTrue("startSession no longer stops the previous session", stop >= 0)
		assertTrue(
			"startSession does not drop the dead cookies, so a tab that has reconnected " +
				"many times carries every old authority into its first request again",
			prune >= 0
		)
		assertTrue("the dead cookies are dropped before the previous session is stopped", prune > stop)
	}

	@Test
	fun `a 431 removes the dead cookies before it empties the jar`() {
		val handler = body(source(), "override fun onReceivedHttpError(")
		val prune = handler.indexOf("dropDeadAuthCookies(")
		val clear = handler.indexOf("removeAllCookies")
		assertTrue("the 431 handler no longer tries the cleanup by name", prune >= 0)
		assertTrue(
			"the 431 handler empties the shared jar before trying the cleanup that keeps " +
				"the other tabs signed in",
			clear > prune
		)
		assertTrue(
			"the 431 handler reloads without a retry guard, so a 431 that cookies did not " +
				"cause reloads for ever",
			handler.contains("jarRetryUrl")
		)
	}

	@Test
	fun `a page is not loaded until the dead cookies are gone`() {
		val render = body(source(), "private fun renderContent()")
		val prune = render.indexOf("dropDeadAuthCookies(pageUrl)")
		val load = render.indexOf("fun load()")
		assertTrue("renderContent does not drop the dead cookies before loading", prune >= 0)
		assertTrue("renderContent loads the page before the cleanup", prune < load)
	}

	@Test
	fun `the pre-load check and the 431 handler share one budget`() {
		val render = body(source(), "private fun renderContent()")
		val handler = body(source(), "override fun onReceivedHttpError(")
		assertTrue(
			"renderContent no longer reads COOKIE_HEADER_BUDGET",
			render.contains("COOKIE_HEADER_BUDGET")
		)
		assertTrue(
			"the 431 handler no longer reads COOKIE_HEADER_BUDGET",
			handler.contains("COOKIE_HEADER_BUDGET")
		)
		assertFalse(
			"the pre-load check uses a size literal again, so it can drift from the 431 handler",
			render.contains("> 8000")
		)
		assertFalse(
			"the 431 handler uses a size literal again, so it can drift from the pre-load check",
			handler.contains("> 8000")
		)
	}
}
