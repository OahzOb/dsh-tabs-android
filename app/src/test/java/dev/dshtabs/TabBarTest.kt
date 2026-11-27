package dev.dshtabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab bar's hit areas, which are most of the interface on a device with no window
 * chrome around it.
 *
 * **Reported from use: switching tabs disconnected one instead.** The × sat on every
 * tab — 22dp square, 4dp from the label — so a tap aimed at a tab's right edge landed
 * on the close control, the session stopped, and the way back was a CONNECT. The row
 * itself was 36dp tall, under the platform's 44dp touch guidance.
 *
 * The fix is not a smaller × on fewer tabs: a tab is only a switch now. No button on
 * it can end a machine's session, and Disconnect lives in the device dialog opened by
 * long-press, with a full-width 48dp control of its own.
 *
 * Nothing in the JVM test source set can inflate a layout or start an activity, so this
 * reads `MainActivity.kt` and the resource XML as text — the compromise
 * [TabDotResourcesTest] and [TabClickTest] make, with the same warning attached. What it
 * buys is that the old arrangement cannot come back silently: a control on the row, an
 * action in a tab's click handler, and a device dialog without Disconnect all fail here
 * with a reason rather than at the next stray tap on a phone.
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
	fun `a tab has no control that can end a connection`() {
		val layout = res("layout/tab.xml")
		assertFalse(
			"tab.xml still declares `@+id/close`. That button is what let a tap aimed at " +
				"a tab disconnect that machine instead of selecting it — the report this " +
				"change answers. A tab is only a switch; the destructive action belongs " +
				"in the device dialog opened by long-press.",
			layout.contains("@+id/close")
		)
		assertFalse(
			"tab.xml still draws a × in the row. Even without an id it is a second, " +
				"smaller target beside the one the finger meant, and a tab is meant to " +
				"have exactly one target: the whole row.",
			layout.contains("android:text=\"×\"")
		)
	}

	@Test
	fun `renderTabs wires no action into a tab`() {
		val render = body(source(), "private fun renderTabs()")
		for (fragment in listOf("close.visibility", "R.id.close", "stopTab", "maybeConnect")) {
			assertFalse(
				"`renderTabs` still names `$fragment`. A tab is only a switch: a tap " +
					"selects a machine and does nothing else, while ending a session takes " +
					"a long-press and the device dialog. Wiring the action back into the " +
					"row is how a stray tap cost somebody a live connection.",
				render.contains(fragment)
			)
		}
		assertTrue(
			"a tab's long-press no longer opens the device dialog, so Disconnect and the " +
				"other machine actions have no way to be reached from the bar",
			render.contains("showDeviceDialog")
		)
	}

	@Test
	fun `a tab row is at least 44dp tall`() {
		val height = dp(res("layout/tab.xml"), "android:layout_height")
		assertTrue(
			"the tab row is ${height}dp, under the platform's 44dp touch guidance. It is " +
				"the only target on a tab now, so the whole row has to be touch-sized.",
			height != null && height >= 44
		)
	}

	@Test
	fun `the label keeps a readable, touch-sized row`() {
		val layout = res("layout/tab.xml")
		val label = layout.substringAfter("@+id/label")
		val margin = dp(label, "android:layout_marginStart")
		val maxWidth = dp(label, "android:maxWidth")
		assertTrue(
			"the label is ${margin}dp from the dot and ${maxWidth}dp wide. With no × to " +
				"give a tab its shape, the label and the row's padding are what make the " +
				"switch readable and tappable; both have to survive the removal.",
			margin != null && margin >= 7 && maxWidth != null && maxWidth >= 100
		)
		assertTrue(
			"the label no longer ellipsizes at the end, so several machines with long " +
				"names can overlap in the bar instead of staying in their own row",
			label.contains("android:ellipsize=\"end\"")
		)
		for (attribute in listOf("android:paddingStart", "android:paddingEnd")) {
			val padding = dp(layout, attribute)
			assertTrue(
				"the tab row's $attribute is ${padding}dp, less than the 10dp that keeps " +
					"the dot and label in from neighbouring tabs and their taps",
				padding != null && padding >= 10
			)
		}
	}

	@Test
	fun `the device dialog exposes a full-size Disconnect for a running machine`() {
		val dialog = res("layout/dialog_device.xml")
		assertTrue(
			"the device dialog has no `@+id/disconnect`. Disconnect moved off the tab to " +
				"stop stray taps ending sessions, so removing it here leaves no way to " +
				"end a connection at all",
			dialog.contains("@+id/disconnect")
		)
		val disconnect = dialog.substringAfter("@+id/disconnect")
		val minHeight = dp(disconnect, "android:minHeight")
		assertTrue(
			"Disconnect in the device dialog is ${minHeight}dp tall. It is the one " +
				"destructive control now, so it must be a real touch target rather than " +
				"the 24dp × it replaced",
			minHeight != null && minHeight >= 48
		)
		assertTrue(
			"Disconnect is not hidden by default, so an idle machine gets a red button " +
				"that can only say it is not connected — a control that teaches the " +
				"operator nothing and takes a permanent tap target on the form",
			disconnect.contains("android:visibility=\"gone\"")
		)

		val show = body(source(), "private fun showDeviceDialog(existing: Device?)")
		for (fragment in listOf(
			"R.id.disconnect", "View.VISIBLE", "State.RUNNING", "State.STARTING",
			"stopTab", "render()", "dialog.dismiss()"
		)) {
			assertTrue(
				"`showDeviceDialog` names no `$fragment`, so the Disconnect control it " +
					"declares is never wired to a live machine. The layout check above can " +
					"pass while the button does nothing.",
				show.contains(fragment)
			)
		}
	}
}
