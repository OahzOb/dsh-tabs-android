package dev.dshtabs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab bar's hit areas, which are most of the interface on a device with no window
 * chrome around it.
 *
 * **Reported from use: switching tabs disconnected one instead.** The × sat on every
 * tab — 22dp square, 4dp from the label — so a tap aimed at a tab's right edge landed
 * on the close control, the session stopped, and the way back was a CONNECT. The row
 * itself was 36dp tall, under the platform's 44dp touch guidance: the target for
 * "select this tab" was smaller than the accidental one beside it.
 *
 * Nothing in the JVM test source set can inflate a layout or start an activity, so this
 * reads `MainActivity.kt` and `res/layout/tab.xml` as text — the compromise
 * [TabDotResourcesTest] and [TabClickTest] make, with the same warning attached. What
 * it buys is that the two properties which made the mistake possible cannot come back
 * silently: the close control is tied to the selection, and the row is a touch-sized
 * target with the control separated from the label.
 */
class TabBarTest {

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

	/** One file under `res/`, with the two candidate roots Gradle may be run from. */
	private fun res(relative: String): String {
		val candidates = listOf(
			java.io.File("src/main/res"),
			java.io.File("app/src/main/res")
		)
		for (candidate in candidates) {
			val file = java.io.File(candidate, relative)
			if (file.isFile) return file.readText()
		}
		throw AssertionError(
			"no resource directory to check: neither ${candidates.first().absolutePath} " +
				"nor ${candidates.last().absolutePath} exists. Gradle runs this task from " +
				"the module directory; run it from one of those instead of passing."
		)
	}

	/** The first `<attribute>="<n>dp"` in a text, as a number. */
	private fun dp(text: String, attribute: String): Int? =
		Regex(attribute + "=\"(\\d+)dp\"").find(text)?.groupValues?.get(1)?.toIntOrNull()

	@Test
	fun `only the selected tab shows a close control`() {
		val render = body(source(), "private fun renderTabs()")
		assertTrue(
			"renderTabs no longer sets `close.visibility`, so the close control is back " +
				"on every tab and a tap aimed at one can disconnect it instead of selecting it",
			render.contains("close.visibility")
		)
		assertTrue(
			"the close control is not tied to the selected tab, so switching tabs can " +
				"still disconnect one by accident",
			render.contains("index == activeIndex")
		)
		assertTrue(
			"the close control is `GONE` on unselected tabs, which changes the row's " +
				"width and shifts the next tab under the operator's finger; it must keep " +
				"its space and just not be drawable",
			render.contains("View.INVISIBLE")
		)
	}

	@Test
	fun `a tab row is at least 44dp tall`() {
		val height = dp(res("layout/tab.xml"), "android:layout_height")
		assertTrue(
			"the tab row is ${height}dp, under the platform's 44dp touch guidance, so the " +
				"target for selecting a tab is smaller than the close control beside it",
			height != null && height >= 44
		)
	}

	@Test
	fun `the close control is separated from the label`() {
		val close = res("layout/tab.xml").substringAfter("@+id/close")
		val margin = dp(close, "android:layout_marginStart")
		assertTrue(
			"the close control is ${margin}dp from the label, close enough to catch a tap " +
				"aimed at the tab; the report was exactly that",
			margin != null && margin >= 8
		)
	}

	@Test
	fun `the close control is a real target when it is shown`() {
		val close = res("layout/tab.xml").substringAfter("@+id/close")
		val width = dp(close, "android:layout_width")
		assertTrue(
			"the close control is ${width}dp wide, and it is the destructive action on " +
				"the row — it should be at least 24dp now that only one tab shows it",
			width != null && width >= 24
		)
	}
}
