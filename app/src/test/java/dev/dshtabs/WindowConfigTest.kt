package dev.dshtabs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MainActivity`'s `configChanges` list is a page-preservation contract, and this is what
 * holds it.
 *
 * A configuration the activity does not list destroys and recreates it. Since the
 * `Sessions` registry that recreate no longer ends a connection — that fix is
 * [SessionSurvivalTest]'s subject — but it does rebuild the WebView, and the remote
 * Harness is a single-page application: the page reloads and what was on it is gone.
 * Every *window* configuration is therefore in the list, and losing one costs a reload
 * on something as ordinary as a split-screen resize or an OEM's compatibility density.
 *
 * **The list was first read as the cause of the pad's split-screen disconnect, and it was
 * not.** A split screen on stock Android resizes the activity in place, with or without
 * `smallestScreenSize`, which the emulator showed by keeping the connection on the old
 * five-flag build; the disconnect was the session belonging to the window, and it is the
 * registry that fixes it. The list is hardening: what keeps a window change from
 * costing even the page on a platform that does recreate for one, and what will still be
 * needed if the registry ever has to be simplified away.
 *
 * Nothing in the JVM test source set can start an activity or deliver a configuration
 * change, and Robolectric is not a dependency here, so this reads the manifest as text
 * — the same compromise [TabClickTest] and [TabDotResourcesTest] make, with the same
 * warning: it proves the declared list, not the framework's behaviour. What it buys is
 * that the attribute cannot lose a window configuration without somebody deleting a
 * test whose failure message says what that costs.
 */
class WindowConfigTest {

	private fun manifest(): String {
		val candidates = listOf(
			java.io.File("src/main/AndroidManifest.xml"),
			java.io.File("app/src/main/AndroidManifest.xml")
		)
		for (candidate in candidates) {
			if (candidate.isFile) return candidate.readText()
		}
		throw AssertionError(
			"no AndroidManifest.xml to check: neither ${candidates.first().absolutePath} " +
				"nor ${candidates.last().absolutePath} exists. Gradle runs this task from " +
				"the module directory; run it from one of those instead of passing."
		)
	}

	/**
	 * The `<activity>` tag that declares `MainActivity`, with its comment stripped.
	 *
	 * The element rather than the file, because `configChanges` on another component
	 * would be a different claim — and the file's own documentation above the activity
	 * mentions the attribute by name, which a plain text search would find.
	 */
	private fun mainActivityTag(): String {
		val text = manifest()
		val name = text.indexOf("android:name=\".MainActivity\"")
		assertTrue(
			"AndroidManifest.xml does not declare `.MainActivity`, so this check cannot " +
				"see the activity it is about — the test would pass while proving nothing",
			name >= 0
		)
		val start = text.lastIndexOf("<activity", name)
		assertTrue("`.MainActivity` is not inside an <activity> tag", start >= 0)
		val end = text.indexOf('>', name)
		assertTrue("the MainActivity tag is never closed", end > name)
		return text.substring(start, end + 1)
	}

	private fun configChanges(): List<String> {
		val tag = mainActivityTag()
		val match = Regex("""android:configChanges\s*=\s*"([^"]*)"""").find(tag)
		assertTrue(
			"MainActivity has no `configChanges` attribute. Every window configuration " +
				"then recreates the activity, which rebuilds every WebView: the connection " +
				"survives in the registry, but the page — and the conversation on it — does " +
				"not. See MainActivity.onDestroy.",
			match != null
		)
		val value = match!!.groupValues[1]
		assertTrue("MainActivity's `configChanges` is empty", value.isNotBlank())
		return value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
	}

	@Test
	fun `every window configuration is handled, so a resize cannot rebuild the page`() {
		val declared = configChanges().toSet()
		// The window configurations, and the rebuild each one avoids. `smallestScreenSize`
		// is the one a split-screen resize raises; the rest are listed because a window
		// can change any of them without the operator asking for anything.
		val window = mapOf(
			"screenSize" to "a resize (split screen, freeform, a folding device)",
			"smallestScreenSize" to "a split-screen window smaller than the last one",
			"screenLayout" to "the window's size class",
			"orientation" to "rotation",
			"density" to "an OEM's compatibility scaling in split screen, or a display change",
			"keyboardHidden" to "the keyboard opening or closing",
			"navigation" to "a keyboard, dock or navigation-bar change",
			"uiMode" to "the light/dark switch",
			"colorMode" to "a wide-colour or HDR display change"
		)
		for ((config, why) in window) {
			assertTrue(
				"MainActivity does not handle `$config`. A configuration not in " +
					"`configChanges` recreates the activity, so $why rebuilds the WebView " +
					"and reloads the remote Harness — a lost page, even though the registry " +
					"keeps the connection. Every window configuration belongs in the list.",
				config in declared
			)
		}
	}
}
