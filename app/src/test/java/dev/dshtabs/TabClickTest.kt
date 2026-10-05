package dev.dshtabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selecting a tab must not open a connection.
 *
 * This was reported from use: tapping a tab connected to that machine — silently when
 * the app holds a key, and through a password dialog when it does not. The README's
 * contract is that switching tabs changes visibility and nothing else, and the tab row
 * ended every tap in `maybeConnect(index)` regardless.
 *
 * Nothing in the JVM test source set can start an activity, and Robolectric is not a
 * dependency here, so this reads `MainActivity.kt` as text — the same compromise
 * [TabDotResourcesTest] makes, for the same reason, with the same warning attached: it
 * can prove that the handler does not *name* a connect call, not that no connection can
 * happen. What it buys is that the call cannot come back without somebody deleting a
 * test whose failure message says why it must not.
 */
class TabClickTest {

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

	/** The tab row's click handler, with its braces matched. */
	private fun tabClickListener(): String {
		val text = source()
		val start = text.indexOf("item.setOnClickListener")
		assertTrue(
			"there is no `item.setOnClickListener` in MainActivity, so this check cannot " +
				"see the tab row's handler at all — the test would pass while proving nothing",
			start >= 0
		)
		val open = text.indexOf('{', start)
		assertTrue("the tab row's click listener has no body", open >= 0)
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
		throw AssertionError("the tab row's click listener is never closed")
	}

	@Test
	fun `tapping a tab selects it and starts nothing`() {
		val handler = tabClickListener()
		for (call in listOf("maybeConnect", "startSession", "askPassword", "reconnect", "stopTab")) {
			assertFalse(
				"the tab row's click handler calls `$call`. A tap on a tab chooses which " +
					"machine is on screen and does nothing else — connecting is what the " +
					"CONNECT button is for, and dialling out from a stray tap is the " +
					"surprise that button exists to prevent. Reported from use.",
				handler.contains(call)
			)
		}
		assertTrue(
			"the handler no longer makes the tapped tab active, so it does not do the " +
				"one thing it is for",
			handler.contains("activeIndex = index")
		)
	}
}
