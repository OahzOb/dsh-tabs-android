package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split between the two halves of a release's notes.
 *
 * This is the rule the update dialog follows: a Chinese phone gets the Chinese half,
 * everyone else gets the English one, and a body with only one half is shown as it is
 * with no switch. It is checked here because the failure is invisible from a unit
 * test of the dialog — the dialog would show *something* either way, and the bug was
 * that the something was the wrong language.
 */
class ReleaseNotesTest {

	private val body = """
		English | [中文](#中文)

		dsh-tabs for Android **0.2.11** — the notes, in English.

		## What is new

		- A thing.

		---

		<a id="中文"></a>
		# 中文

		dsh-tabs for Android **0.2.11** —— 中文说明。

		## 0.2.11 有什么

		- 一件事。
	""".trimIndent()

	@Test
	fun `the two halves are separated and the header is not part of either`() {
		val sections = ReleaseNotes.split(body)
		assertTrue("English half", sections.english.startsWith("dsh-tabs for Android"))
		assertTrue("English body", sections.english.contains("## What is new"))
		assertFalse("no Chinese in the English half", sections.english.contains("中文说明"))
		assertFalse("no header in the English half", sections.english.contains("English |"))
		assertTrue("Chinese half", sections.chinese!!.startsWith("dsh-tabs for Android"))
		assertTrue("Chinese body", sections.chinese!!.contains("0.2.11 有什么"))
		assertFalse("the anchor is gone", sections.chinese!!.contains("a id="))
		assertFalse("the heading is gone", sections.chinese!!.contains("# 中文"))
	}

	@Test
	fun `a Chinese phone gets the Chinese half and an English one the English half`() {
		assertTrue(ReleaseNotes.preferred(body, "zh-CN").contains("中文说明"))
		assertTrue(ReleaseNotes.preferred(body, "zh-Hant-TW").contains("中文说明"))
		assertTrue(ReleaseNotes.preferred(body, "en-GB").contains("in English"))
		assertTrue(ReleaseNotes.preferred(body, "de").contains("in English"))
	}

	@Test
	fun `the heading alone is a marker when the anchor is missing`() {
		val text = "English half.\n\n---\n\n# 中文\n\n中文半部分。"
		val sections = ReleaseNotes.split(text)
		assertEquals("English half.", sections.english)
		assertEquals("中文半部分。", sections.chinese)
	}

	@Test
	fun `a single-language body stays whole and offers no switch`() {
		val sections = ReleaseNotes.split("Only English here.\n\n- one line")
		assertEquals("Only English here.\n\n- one line", sections.english)
		assertNull(sections.chinese)
		assertFalse(sections.switchable)
		assertEquals("Only English here.\n\n- one line", ReleaseNotes.preferred("Only English here.\n\n- one line", "zh-CN"))
	}

	@Test
	fun `an empty Chinese half is not a half`() {
		val sections = ReleaseNotes.split("English.\n\n<a id=\"中文\"></a>\n\n   \n")
		assertNull(sections.chinese)
		assertFalse(sections.switchable)
	}

	@Test
	fun `language tags are matched by prefix, case-insensitively`() {
		assertTrue(ReleaseNotes.isChinese("zh"))
		assertTrue(ReleaseNotes.isChinese("ZH-CN"))
		assertFalse(ReleaseNotes.isChinese("en"))
		assertFalse(ReleaseNotes.isChinese(""))
	}
}
