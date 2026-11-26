package dev.dshtabs

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * The release feed read the second way: the pages GitHub serves to a browser.
 *
 * The updater's first channel is the public API — one request that names the tag, the
 * notes, the APK's URL, its size and its SHA-256. What its documentation says in
 * passing and a phone can be forgiven for not noticing: **an unauthenticated caller is
 * allowed 60 requests an hour per network address**, and an address is not a phone.
 * Behind carrier NAT, a shared office NAT or a proxy that sells one exit to hundreds of
 * people, that budget belongs to everybody at once. The failure arrives as `403`, which
 * the app used to report as "GitHub answered HTTP 403" — true, and useless.
 *
 * So the same question has a second channel, the one a browser uses. `/releases/latest`
 * answers with a redirect to the tag; the expanded asset fragment names the APK and its
 * digest; `releases.atom` carries the notes. **None of them are counted against the
 * API's quota.** [Updates] does the requests; this file only reads what they answered,
 * because parsing is the part a build-machine test can pin without a network — and the
 * part that fails silently for a year when it is wrong.
 */

/** The repository both channels read their releases from. */
internal const val RELEASE_REPOSITORY = "OahzOb/dsh-tabs-android"

/** One `.apk` asset of a release, as the release page names it. */
internal data class ReleaseAsset(val url: String, val sha256: String?)

/** How much of GitHub's error message is worth repeating in a toast. */
private const val FAILURE_DETAIL_MAX = 160

/** The path every downloadable asset sits under, relative to `github.com`. */
private const val DOWNLOAD_PATH = "/$RELEASE_REPOSITORY/releases/download/"

/** One entry of the releases feed. */
private val FEED_ENTRY = Regex("(?s)<entry>(.*?)</entry>")

/** The release notes inside one feed entry. */
private val FEED_CONTENT = Regex("(?s)<content[^>]*>(.*?)</content>")

/** One `.apk` link inside the expanded asset fragment. */
private val ASSET_LINK = Regex("href=\"(${Regex.escape(DOWNLOAD_PATH)}[^\"]+?\\.apk)\"")

/** The digest GitHub prints beside a release asset, when it prints one. */
private val ASSET_DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

/** An HTML tag, which has no meaning in a dialog. */
private val HTML_TAG = Regex("(?s)<[^>]*>")

/** A `<br>` and the newline the feed leaves after it, which are one line break. */
private val HTML_LINE_BREAK = Regex("(?i)<br\\s*/?>[ \\t]*\\r?\\n?")

/** A tag that ends a paragraph, so the next one starts after a blank line. */
private val HTML_PARAGRAPH_END = Regex("(?i)</(p|h[1-6]|pre|div|blockquote|tr)>")

/** A tag that ends one item of a list, which is a line of its own. */
private val HTML_ITEM_END = Regex("(?i)</li>")

/** Horizontal whitespace, collapsed after the tags are gone. */
private val HORIZONTAL_SPACE = Regex("[ \\t\\u00a0]+")

/** Spaces around a newline, which only pad the result. */
private val SPACE_AROUND_NEWLINE = Regex(" *\\n *")

/** More than one blank line, which a dialog does not need. */
private val BLANK_LINES = Regex("\\n{3,}")

/** Any run of whitespace, used to flatten a server's error message. */
private val WHITESPACE = Regex("\\s+")

/** A numeric character reference, decimal or `&#x` hexadecimal. */
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]{1,8});")

/**
 * The tag a `/releases/latest` redirect points at.
 *
 * The redirect is the browser channel's answer to "which release is the latest" —
 * draft and pre-release excluded, exactly like the API's endpoint — and its `Location`
 * ends in `/releases/tag/<tag>`. The tag is then used as a path segment of two more
 * URLs, so anything that is not a plain tag character is refused rather than escaped:
 * a redirect that names `v0.2.5/../evil` is not a tag this updater will go looking for.
 *
 * @param location - the `Location` header, which may be null or anything else.
 * @returns the tag, `v` included, or null when the header named no release.
 */
internal fun releaseTag(location: String?): String? {
	val after = location?.substringAfter("/releases/tag/", "") ?: return null
	val tag = after.substringBefore('?').substringBefore('#').trim().trimEnd('/')
	if (tag.isEmpty() || tag == "." || tag == "..") return null
	if (tag.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return null
	return tag
}

/**
 * The APK a release page's expanded asset fragment names, with the digest the page
 * prints beside it.
 *
 * This is the fragment `github.com/<repo>/releases/expanded_assets/<tag>` serves to the
 * release page itself — the list of files, a `<li>` per file. A release with no APK, or
 * an HTML change that moves the link out of a bare `href`, reads as null, and the
 * updater says so instead of inventing a URL. The digest is optional: it has only been
 * printed beside assets since GitHub started reporting it, and its absence must not
 * cost an update.
 *
 * @param fragment - the page body.
 * @returns the absolute download URL and digest, or null when no APK is listed.
 */
internal fun releaseAsset(fragment: String): ReleaseAsset? {
	val link = ASSET_LINK.find(fragment) ?: return null
	// The digest belongs to the row the link is in, not to whatever file follows it.
	val rowEnd = fragment.indexOf("</li>", link.range.last + 1).takeIf { it >= 0 } ?: fragment.length
	val row = fragment.substring(link.range.last + 1, rowEnd)
	val digest = ASSET_DIGEST.find(row)?.groupValues?.get(1)
	return ReleaseAsset(url = "https://github.com${link.groupValues[1]}", sha256 = digest)
}

/**
 * The notes of one release, read from the repository's releases feed.
 *
 * The feed is an Atom document whose entries carry the whole release body as escaped
 * HTML. Matching the entry by its tag link matters: the feed lists several releases,
 * and a prefix compare would hand `v0.2.5` the notes of `v0.2.50`. The body is then
 * turned back into the plain text the dialog shows — the same thing the API's `body`
 * field supplies in markdown — because a dialog is not a browser and `<h2>` has to
 * become a line, not a tag.
 *
 * @param feed - the Atom document.
 * @param tag - the release to read, `v` included.
 * @returns the notes, or null when this feed has no such entry or nothing to say.
 */
internal fun releaseNotes(feed: String, tag: String): String? {
	for (entry in FEED_ENTRY.findAll(feed)) {
		val body = entry.groupValues[1]
		if (!body.contains("releases/tag/$tag\"")) continue
		val content = FEED_CONTENT.find(body)?.groupValues?.get(1) ?: continue
		return noteText(xmlUnescape(content)).ifEmpty { null }
	}
	return null
}

/**
 * The API's JSON, decoded into the updater's own answer.
 *
 * Kept out of [Updates.check] so the shape of the answer can be pinned without a
 * socket: which asset is picked out of the list, that a digest GitHub did not really
 * report is null rather than the word "null", and that a release whose tag is not
 * newer is `Current` even when it carries an APK.
 *
 * @param body - the API's response body.
 * @param currentVersion - the version of the running build.
 * @returns the newer release, `Current`, or why the body could not be used.
 */
internal fun readRelease(body: String, currentVersion: String): Updates.Check {
	val release = try {
		JSONObject(body)
	} catch (_: Exception) {
		return Updates.Check.Failed("the release feed answered with something that is not JSON")
	}
	val version = release.textOrNull("tag_name")?.removePrefix("v")?.trim().orEmpty()
	if (version.isEmpty()) return Updates.Check.Failed("the release has no tag")
	if (compareVersions(version, currentVersion) <= 0) return Updates.Check.Current
	val assets = release.optJSONArray("assets") ?: return Updates.Check.Failed("the release has no assets")
	var apk: JSONObject? = null
	for (index in 0 until assets.length()) {
		val asset = assets.optJSONObject(index) ?: continue
		if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
			apk = asset
			break
		}
	}
	val asset = apk ?: return Updates.Check.Failed("the release has no APK")
	val url = asset.optString("browser_download_url")
	if (url.isEmpty()) return Updates.Check.Failed("the APK has no download URL")
	return Updates.Check.Newer(
		Updates.Available(
			version = version,
			notes = release.textOrNull("body").orEmpty(),
			url = url,
			sha256 = sha256Of(asset),
			size = asset.optLong("size", 0L)
		)
	)
}

/**
 * A string field of the API's JSON, with the two ways of saying "absent" collapsed.
 *
 * Android's `org.json` answers `"null"` for a JSON `null` where the copy the tests link
 * answers `""` — a difference no build-machine test can catch. Either spelling has to
 * read as "no value", not as a version called null or a release note that says "null".
 *
 * @param name - the field.
 * @returns its value, or null when it is missing, blank, or a JSON null.
 */
private fun JSONObject.textOrNull(name: String): String? =
	optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

/**
 * The SHA-256 an asset entry reported, when it reported a usable one.
 *
 * Strict on purpose. `optString` on a JSON `null` answers `""` in the copy the tests
 * link and could answer `"null"` in the platform's copy — a difference no build-machine
 * test can see — and a digest that is not 64 hexadecimal characters is one this app
 * must not pretend to have checked. No digest only costs the extra check: the APK's
 * signature is what decides whether the installer can accept it.
 *
 * @param asset - one entry of the release's `assets` array.
 * @returns the lowercase hex digest, or null when there is no usable one.
 */
private fun sha256Of(asset: JSONObject): String? = asset
	.optString("digest")
	.trim()
	.removePrefix("sha256:")
	.trim()
	.lowercase()
	.takeIf { digest -> digest.length == 64 && digest.all { it.isDigit() || it in 'a'..'f' } }

/**
 * What an API refusal means, in words that name the limit instead of the status.
 *
 * `403` from the release API is almost always the unauthenticated quota, and its body
 * says so — including the network address GitHub saw, which is the one fact that
 * explains a phone failing while a laptop on another network succeeds. The body is
 * trimmed to its first sentence because the rest is an advertisement for authenticated
 * requests, and the reset time is added only when GitHub confirmed the quota is spent
 * (`X-RateLimit-Remaining: 0`); a 403 for any other reason keeps its own message.
 *
 * @param status - the HTTP status.
 * @param message - the `message` field of the API's JSON error, or an empty string.
 * @param remaining - `X-RateLimit-Remaining`, when the response carried it.
 * @param reset - `X-RateLimit-Reset` (epoch seconds), when the response carried it.
 * @returns the sentence the toast shows.
 */
internal fun describeApiFailure(status: Int, message: String, remaining: String?, reset: String?): String {
	val reason = StringBuilder("GitHub answered HTTP $status")
	val detail = message
		.trim()
		.substringBefore("(But here's the good news")
		.replace(WHITESPACE, " ")
		.trim()
		.trimEnd('.', ',', ';')
		.take(FAILURE_DETAIL_MAX)
		.trim()
	if (detail.isNotEmpty()) reason.append(" (").append(detail).append(')')
	if ((status == 403 || status == 429) && remaining?.trim() == "0") {
		// The quota is the address's, not the app's: saying whose makes the failure
		// something the operator can act on (change network, wait) rather than a bug.
		reason.append("; this network address has spent its unauthenticated quota")
		val resetAt = reset?.trim()?.toLongOrNull()
		if (resetAt != null && resetAt > 0) {
			reason.append(" and it resets at ").append(clockTime(resetAt * 1_000L))
		}
	}
	return reason.toString()
}

/**
 * Undo the XML escaping the feed applies to its own HTML payload.
 *
 * Everything in the feed's `<content>` is escaped once for XML, so an encoded tag
 * arrives as `&lt;p&gt;` and an apostrophe as `&#39;`. `&amp;` is undone last: doing
 * it first would turn the literal text `&amp;lt;` into a tag start.
 *
 * @param text - one XML text node.
 * @returns the HTML it stood for.
 */
internal fun xmlUnescape(text: String): String {
	val named = text
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&quot;", "\"")
		.replace("&apos;", "'")
	return decodeNumericEntities(named).replace("&amp;", "&")
}

/**
 * Turn the notes' HTML into the plain text a dialog can show.
 *
 * The API hands the same notes over as markdown, which the dialog prints verbatim.
 * The feed has no markdown, only HTML, so block ends become line breaks, tags
 * disappear, entities are decoded, and runs of whitespace are collapsed — the words,
 * their order and their paragraphing survive, the typography does not. A `<br>` is the
 * one that needs care: the feed leaves its own newline after the tag, so treating both
 * as line ends would double-space every wrapped line.
 *
 * @param html - the notes as HTML.
 * @returns the notes as text, trimmed.
 */
internal fun noteText(html: String): String {
	val deentitied = html
		.replace(HTML_LINE_BREAK, "\n")
		.replace(HTML_PARAGRAPH_END, "\n\n")
		.replace(HTML_ITEM_END, "\n")
		.replace(HTML_TAG, "")
		.replace("&nbsp;", " ")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&quot;", "\"")
		.replace("&#39;", "'")
	return decodeNumericEntities(deentitied)
		.replace("&amp;", "&")
		.replace(HORIZONTAL_SPACE, " ")
		.replace(SPACE_AROUND_NEWLINE, "\n")
		.replace(BLANK_LINES, "\n\n")
		.trim()
}

/**
 * Decode `&#39;`-style references, leaving anything invalid as it was written.
 *
 * @param text - text that may contain numeric references.
 * @returns the same text with the valid ones decoded.
 */
private fun decodeNumericEntities(text: String): String = NUMERIC_ENTITY.replace(text) { match ->
	val radix = if (match.groupValues[1].isEmpty()) 10 else 16
	val code = match.groupValues[2].toIntOrNull(radix)
	when {
		code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF -> match.value
		else -> String(Character.toChars(code))
	}
}

/**
 * The local clock time of an epoch instant, for "the limit resets at".
 * @param epochMillis - milliseconds since the epoch.
 * @returns `HH:mm` in the device's time zone.
 */
private fun clockTime(epochMillis: Long): String {
	val time = Calendar.getInstance().apply { timeInMillis = epochMillis }
	return String.format(Locale.ROOT, "%02d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE))
}
