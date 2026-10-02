package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab dot's state list has to be wired to something, and this is the only thing
 * that says so.
 *
 * **A selector is invisible to every other check this project has.** It compiles, it
 * packages, it renders, and it has no runtime error to catch: a state list whose view
 * never receives the state it selects on simply shows its default colour for ever.
 * That is what shipped — `res/layout/tab.xml` named `@drawable/dot_idle` directly
 * instead of `@drawable/dot`, so `MainActivity.renderTabs` set `isSelected`,
 * `isEnabled` and `isActivated` on a view whose background was a plain grey oval. A
 * connected machine kept a grey dot, and `dot_running`, `dot_starting` and
 * `dot_failed` were unreachable code in a resources directory.
 *
 * Nothing in the JVM test source set can inflate a layout, so these read the resource
 * XML and the Kotlin that drives it as text. That is weaker than rendering, and it is
 * enough for the property that broke: **the set of states the code sets and the set of
 * states the selector reads must be the same set.** A test that only checked for the
 * one wrong string would have caught this bug and not the next one; that comparison
 * catches a state added on either side alone, which is the shape of the mistake.
 *
 * `RemoteParityTest` holds the two clients to each other. This holds one client to
 * itself.
 */
class TabDotResourcesTest {

	/**
	 * The module directory, which is Gradle's working directory for this task.
	 *
	 * Both spellings are tried because which directory Gradle picks is a property of
	 * the build rather than of this test — the same reason `RemoteParityTest` keeps
	 * two candidates for the sibling repository.
	 */
	private fun resDir(): java.io.File {
		val candidates = listOf(
			java.io.File("src/main/res"),
			java.io.File("app/src/main/res")
		)
		for (candidate in candidates) {
			if (candidate.isDirectory) return candidate
		}
		throw AssertionError(
			"no resource directory to check: neither ${candidates.first().absolutePath} " +
				"nor ${candidates.last().absolutePath} exists. Gradle runs this task from " +
				"the module directory; run it from one of those instead of passing."
		)
	}

	fun read(relative: String): String {
		val file = java.io.File(resDir(), relative)
		assertTrue("${file.absolutePath} does not exist", file.isFile)
		return file.readText()
	}

	/**
	 * Every drawable the dot selector chooses between, and the state that chooses it.
	 *
	 * The value is not required to be `"true"`: `android:state_enabled="false"` is how
	 * a selector spells "disabled", and it is the only way — there is no
	 * `state_disabled`. Matching only `"true"` silently dropped that item, and the
	 * comparison below then blamed `MainActivity` for a state the selector did read.
	 */
	private fun selectorItems(): List<Pair<String?, String>> =
		Regex("""<item\b([^>]*)/>""")
			.findAll(read("drawable/dot.xml"))
			.map { match ->
				val attributes = match.groupValues[1]
				val state = Regex("""android:(state_[a-z]+)\s*=\s*"([a-z]+)"""")
					.find(attributes)?.groupValues?.get(1)
				val drawable = Regex("""android:drawable\s*=\s*"@drawable/([a-z_]+)"""")
					.find(attributes)?.groupValues?.get(1)
				assertTrue("an item in dot.xml names no drawable: ${match.value}", drawable != null)
				state to drawable!!
			}
			.toList()

	/** The state setters `renderTabs` applies to the dot. */
	private fun statesTheCodeSets(): Set<String> =
		Regex("""\bdot\.(is[A-Z][A-Za-z]*)\s*=""")
			.findAll(
				java.io.File("src/main/java/dev/dshtabs/MainActivity.kt")
					.takeIf { it.isFile }
					?.readText()
					?: java.io.File("app/src/main/java/dev/dshtabs/MainActivity.kt").readText()
			)
			.map { match ->
				// `isSelected` is `android:state_selected`; the XML spelling is the
				// property with a `state_` prefix and no `is`.
				"state_" + match.groupValues[1].removePrefix("is").replaceFirstChar { it.lowercase() }
			}
			.toSet()

	@Test
	fun `the dot's background is the selector, not one of the states it selects between`() {
		val layout = read("layout/tab.xml")
		val dot = Regex("""<View\b[^>]*android:id\s*=\s*"@\+id/dot".*?/>""", RegexOption.DOT_MATCHES_ALL)
			.find(layout)
		assertTrue("no view with `@+id/dot` in tab.xml", dot != null)
		val background = Regex("""android:background\s*=\s*"@drawable/([a-z_]+)"""")
			.find(dot!!.value)?.groupValues?.get(1)
		assertEquals(
			"the dot's background must be `dot`, the selector — naming a single state " +
				"here makes the other three unreachable and the tab's dot permanently " +
				"that one colour, which is the bug this test exists for",
			"dot",
			background
		)
	}

	@Test
	fun `every state drawable the selector names exists`() {
		val items = selectorItems()
		assertTrue("dot.xml selects between nothing", items.isNotEmpty())
		for ((_, drawable) in items) {
			val file = java.io.File(resDir(), "drawable/$drawable.xml")
			assertTrue(
				"dot.xml names @drawable/$drawable, which does not exist as " +
					"${file.absolutePath}",
				file.isFile
			)
		}
		// The guard on the guard: a selector with no default item shows nothing at all
		// when no state matches, which is a worse failure than the wrong colour.
		assertTrue(
			"dot.xml has no unconditional default item, so an idle tab draws nothing",
			items.any { (state, _) -> state == null }
		)
	}

	@Test
	fun `the states the code sets and the states the selector reads are the same set`() {
		val selected = selectorItems().mapNotNull { (state, _) -> state }.toSet()
		val set = statesTheCodeSets()

		assertEquals(
			"the dot's selector and the states `renderTabs` sets have drifted apart. " +
				"A state in the selector that the code never sets is a colour that can " +
				"never appear; a state the code sets that the selector does not read is " +
				"a connection state with no visible difference from idle.",
			selected,
			set
		)
		// A guard on the comparison: two empty sets are equal, and that would pass
		// while checking nothing at all.
		assertFalse("no states found in the selector", selected.isEmpty())
		assertFalse("no dot state setters found in MainActivity", set.isEmpty())
	}
}
