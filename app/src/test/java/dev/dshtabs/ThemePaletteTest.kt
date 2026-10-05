package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The palette is two files now, and this is what holds them to each other.
 *
 * **A colour missing from one of them is not a build error.** `values-night/colors.xml`
 * is consulted only in the dark, so a name that exists in the light file and not in the
 * dark one resolves to nothing there, and a name in the dark file alone is a view that
 * falls back to the Material library's default — which looks deliberate enough that
 * nobody reports it. The bug reported from use was the other direction: the shell was
 * dark in both modes because a dark palette was all there was.
 *
 * The contrast floors are here for the same reason as the state-set comparison in
 * [TabDotResourcesTest]: the failure is invisible to every other check this project has.
 * A colour pair can be measured, rendered, packaged and shipped with the text on it
 * legible only by shape — which is what the connect button's label was, at 2.05:1,
 * until the pixels of a screenshot were sampled to find out what the theme had actually
 * drawn.
 *
 * Nothing in the JVM test source set can resolve a resource, so these read the XML as
 * text. That is weaker than rendering, and it is enough for the properties that broke.
 */
class ThemePaletteTest {

	/**
	 * The module directory, which is Gradle's working directory for this task.
	 *
	 * Both spellings are tried for the same reason `TabDotResourcesTest` tries both:
	 * which directory Gradle picks is a property of the build rather than of this test.
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

	private fun read(relative: String): String {
		val file = java.io.File(resDir(), relative)
		assertTrue("${file.absolutePath} does not exist", file.isFile)
		return file.readText()
	}

	/**
	 * `name -> #RRGGBB(AA)` for one colour file.
	 *
	 * The name class is deliberately wider than the palette uses today, and the value
	 * class accepts the eight-digit form with alpha. A shape this expression does not
	 * understand is a colour that ends up in *neither* map — so the comparison below
	 * would pass by not seeing it. `every colour in the file is read by this test` is
	 * the guard that stops that happening quietly.
	 */
	private fun palette(relative: String): Map<String, String> =
		Regex("""<color\s+name="([A-Za-z0-9_]+)"\s*>\s*(#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?)\s*</color>""")
			.findAll(read(relative))
			.associate { it.groupValues[1] to it.groupValues[2].uppercase() }

	/**
	 * WCAG relative luminance: sRGB channels linearised, then weighted for the eye.
	 *
	 * The gamma step is not optional — a naive average of the channels rates
	 * `#5B9DFF` on white as acceptable, and this is the formula a contrast checker
	 * uses, so it is the one to hold the palette to.
	 *
	 * An eight-digit value carries alpha, which this ignores: the last six digits are
	 * the colour. A translucent surface is a decision this palette does not make
	 * anywhere, and if one ever appears, the ratio here is the one against its own
	 * colour — which is the useful number to have printed in a failure either way.
	 */
	private fun luminance(hex: String): Double {
		val digits = hex.removePrefix("#").takeLast(6)
		val channels = (0 until 3).map { index ->
			val value = digits.substring(index * 2, index * 2 + 2).toInt(16) / 255.0
			if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
		}
		return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
	}

	private fun contrast(a: String, b: String): Double {
		val first = luminance(a)
		val second = luminance(b)
		return (max(first, second) + 0.05) / (min(first, second) + 0.05)
	}

	private fun assertContrast(
		mode: String,
		colours: Map<String, String>,
		ink: String,
		surface: String,
		floor: Double
	) {
		val ratio = contrast(colours.getValue(ink), colours.getValue(surface))
		assertTrue(
			"$mode palette: `$ink` (${colours.getValue(ink)}) on `$surface` " +
				"(${colours.getValue(surface)}) is ${"%.2f".format(ratio)}:1, under the " +
				"$floor:1 floor",
			ratio >= floor
		)
	}

	private fun bothModes(): List<Pair<String, Map<String, String>>> = listOf(
		"light" to palette("values/colors.xml"),
		"dark" to palette("values-night/colors.xml")
	)

	@Test
	fun `every colour in the file is read by this test`() {
		// **A guard on the guard.** The two checks below can only compare colours they
		// can parse, so a name or a value shape the expression above does not match is
		// invisible to both: it is missing from its own map, and two maps missing the
		// same entry are still equal. Counting elements instead of trusting the
		// expression is what turns "these two agree" into "these two agree about
		// everything in the file".
		for ((mode, file) in listOf("light" to "values/colors.xml", "dark" to "values-night/colors.xml")) {
			val text = read(file)
			val declared = Regex("""<color\b""").findAll(text).count()
			val parsed = palette(file).size
			assertEquals(
				"the $mode palette declares $declared <color> elements and this test parsed " +
					"$parsed of them. A colour it cannot parse is absent from both maps, so " +
					"the name and contrast checks pass while saying nothing about it",
				declared,
				parsed
			)
			assertTrue("the $mode palette declares no colours at all", declared > 0)
		}
	}

	@Test
	fun `both modes define the same colour names`() {
		val (lightMode, light) = bothModes()[0]
		val (darkMode, dark) = bothModes()[1]
		assertTrue("the $lightMode palette defines nothing", light.isNotEmpty())
		assertEquals(
			"the two palettes have drifted apart. A name in one mode and not the other " +
				"is a view that is a different colour there, or that falls back to the " +
				"library's default — neither of which fails to build, and neither of " +
				"which anybody reports as a bug.",
			light.keys.sorted(),
			dark.keys.sorted()
		)
		assertTrue("the $darkMode palette defines nothing", dark.isNotEmpty())
	}

	@Test
	fun `every ink is legible on the surface it is drawn on, in both modes`() {
		for ((mode, colours) in bothModes()) {
			// Text, held to the WCAG AA floor for body text.
			assertContrast(mode, colours, "fg", "bg", 4.5)
			assertContrast(mode, colours, "dim", "bg", 4.5)
			// The panel's title and reason; and the transcript, which sits on `bar`.
			assertContrast(mode, colours, "dim", "bar", 4.5)
			// The connect button: its label on the accent fill.
			assertContrast(mode, colours, "on_accent", "accent", 4.5)
			// And the accent as *text* — which is what a dialog's buttons are drawn
			// in — on the surface a dialog sits on.
			assertContrast(mode, colours, "accent", "bar", 4.5)
			// The tab's state dot is a graphic, not a word, so the non-text floor
			// applies to it.
			for (state in listOf("ok", "warn", "bad")) {
				assertContrast(mode, colours, state, "bar", 3.0)
			}
		}
	}

	@Test
	fun `the theme follows the system instead of forcing one mode`() {
		val theme = read("values/themes.xml")
		val parent = Regex("""<style\s+name="Theme\.DshTabs"\s+parent="([^"]+)"""")
			.find(theme)?.groupValues?.get(1)
		assertTrue("no Theme.DshTabs style with a parent in values/themes.xml", parent != null)
		assertTrue(
			"the theme is parented on `$parent`. Without `DayNight` the two palettes are " +
				"never selected between and the shell is one mode whatever the system is " +
				"set to, which is the bug this test exists for",
			parent!!.contains("DayNight")
		)
		// The system-bar icons have to invert with the shell, and they do it from one
		// style rather than two: `isLightTheme` is the attribute the DayNight parent
		// flips, so there is no second copy of this style to drift.
		for (attribute in listOf("android:windowLightStatusBar", "android:windowLightNavigationBar")) {
			assertTrue(
				"$attribute is not bound to `?android:attr/isLightTheme`, so the system " +
					"bar icons do not invert when the shell does",
				theme.contains("""<item name="$attribute">?android:attr/isLightTheme</item>""")
			)
		}
	}

	@Test
	fun `the palette files are the two modes and nothing else`() {
		// A guard on the whole arrangement: if the dark palette were ever folded back
		// into `values/`, the tests above would still pass — comparing one file with
		// itself — and the app would be dark-only again.
		assertTrue(
			"values-night/colors.xml is where the dark palette has to live for the " +
				"system to select it",
			java.io.File(resDir(), "values-night/colors.xml").isFile
		)
	}
}
