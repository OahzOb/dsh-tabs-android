package dev.dshtabs

/**
 * The bilingual shape of a release's notes, reduced to the part a dialog can show.
 *
 * Every release of this client is published with **two notes in one body**: English
 * first, then a `# 中文` section after a marker. The update dialog used to print the
 * body verbatim, which meant a phone whose language is Chinese was shown the English
 * half — and because the dialog truncates at 4 000 characters, the Chinese half was
 * often not in the text at all. A wrong language that cannot be changed is worse than
 * a short note, so the dialog asks this class which half to show and puts a switch on
 * the button row.
 *
 * **The split is by the document's own markers, not by language detection.** The
 * header line, the `<a id="中文"></a>` anchor and the `# 中文` heading are the
 * conventions the release notes already follow; picking text out by "does it look
 * Chinese" would break on a note that quotes an English command, and it would be a
 * different answer on every release.
 *
 * Notes without a Chinese half stay whole: the dialog then shows them exactly as
 * published and offers no switch, because a button that switches to nothing is worse
 * than no button.
 */
object ReleaseNotes {

	/** The two halves of one body, either of which may be absent. */
	data class Sections(val english: String, val chinese: String?) {

		/** The half to show for a language tag such as `zh-CN` or `en`. */
		fun forLanguage(language: String): String =
			if (isChinese(language) && chinese != null) chinese else english

		/** Whether there is a choice to offer. */
		val switchable: Boolean get() = chinese != null
	}

	/** The anchor the notes put in front of the Chinese section. */
	private val ANCHOR = Regex("(?m)^\\s*<a id=\"中文\"></a>\\s*$")

	/** The heading the section starts with when the anchor is not there. */
	private val HEADING = Regex("(?m)^#\\s*中文\\s*$")

	/** The bilingual header line. */
	private val HEADER = Regex("(?m)^English\\s*\\|\\s*\\[中文]\\(#中文\\)\\s*$")

	/** A markdown horizontal rule, which only separates the two halves. */
	private val RULE = Regex("(?m)^-{3,}\\s*$")

	/**
	 * Split one release body into its halves.
	 *
	 * @param body the notes as published (markdown, from the release API).
	 * @returns the English half, and the Chinese half when the body has one.
	 */
	fun split(body: String): Sections {
		val text = body.replace("\r\n", "\n")
		val marker = ANCHOR.find(text) ?: HEADING.find(text)
		if (marker == null) return Sections(clean(text), null)
		val english = clean(text.substring(0, marker.range.first))
		val chinese = clean(text.substring(marker.range.last + 1)).ifBlank { null }
		return Sections(english, chinese)
	}

	/**
	 * The half to show for a language tag.
	 *
	 * A body without a Chinese half answers its English half for every language, so a
	 * caller does not need to test [Sections.chinese] before asking.
	 */
	fun preferred(body: String, language: String): String = split(body).forLanguage(language)

	/** True for a Chinese language tag; `zh`, `zh-CN` and `zh-Hant-TW` all count. */
	fun isChinese(language: String): Boolean = language.lowercase().startsWith("zh")

	/**
	 * Drop what only existed to join the two halves: the header line, the rule, and
	 * the Chinese heading. The body is otherwise shown as the release published it,
	 * markdown and all — the dialog is not a renderer, and a half-edited rendition is
	 * how a command in a note comes out altered.
	 */
	private fun clean(text: String): String =
		text
			.replace(HEADER, "")
			.replace(RULE, "")
			.replace(HEADING, "")
			.trim()
}
