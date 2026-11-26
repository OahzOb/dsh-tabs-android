package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the release page and its feed — the channel the updater falls back to when
 * the API's quota for a shared network address is spent.
 *
 * Every parser here can fail in a way that looks like success: a redirect tag that is
 * not a tag, an APK link that belongs to a different release, notes matched by prefix
 * against the wrong entry. Those failures do not throw, they surface months later as
 * "the updater offers a version that does not exist". So each one is pinned with the
 * text a real page serves, including the rows and entries that must *not* be picked.
 */
class UpdateFeedTest {

	// ------------------------------------------------------------------ redirect

	@Test
	fun `the latest redirect names the tag at the end of its location`() {
		assertEquals(
			"v0.2.5",
			releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5")
		)
		assertEquals(
			"v0.2.5",
			releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5?x=1")
		)
		assertEquals(
			"v0.2.5",
			releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5/")
		)
	}

	@Test
	fun `a location with no tag, or a tag with path characters, is refused`() {
		assertNull(releaseTag(null))
		assertNull(releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases"))
		assertNull(releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/"))
		assertNull(releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5/../evil"))
		assertNull(releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/."))
		assertNull(releaseTag("https://github.com/OahzOb/dsh-tabs-android/releases/tag/.."))
	}

	// --------------------------------------------------------------------- asset

	@Test
	fun `the asset fragment gives the apk url and its digest`() {
		val asset = releaseAsset(
			"""
			<ul>
			  <li>
			    <a href="/OahzOb/dsh-tabs-android/releases/download/v0.2.5/dsh-tabs-android-0.2.5.apk" rel="nofollow">
			      <span class="text-bold">dsh-tabs-android-0.2.5.apk</span>
			    </a>
			    <span class="Truncate-text">sha256:4e19c5c9d55563a307e6c5ba0f18e755625bc556f604c7c731a36ab44e410c60</span>
			  </li>
			  <li>
			    <a href="/OahzOb/dsh-tabs-android/archive/refs/tags/v0.2.5.zip">Source code</a>
			  </li>
			</ul>
			""".trimIndent()
		)
		assertEquals(
			"https://github.com/OahzOb/dsh-tabs-android/releases/download/v0.2.5/dsh-tabs-android-0.2.5.apk",
			asset?.url
		)
		assertEquals("4e19c5c9d55563a307e6c5ba0f18e755625bc556f604c7c731a36ab44e410c60", asset?.sha256)
	}

	@Test
	fun `a digest from the next row is not borrowed`() {
		val asset = releaseAsset(
			"""
			<li>
			  <a href="/OahzOb/dsh-tabs-android/releases/download/v0.2.5/dsh-tabs-android-0.2.5.apk">apk</a>
			</li>
			<li>
			  <span>sha256:4e19c5c9d55563a307e6c5ba0f18e755625bc556f604c7c731a36ab44e410c60</span>
			</li>
			""".trimIndent()
		)
		assertNull("an APK with no digest of its own must not take the next file's", asset?.sha256)
	}

	@Test
	fun `a fragment with no apk names nothing`() {
		assertNull(releaseAsset("<li><a href=\"/OahzOb/dsh-tabs-android/archive/refs/tags/v0.2.5.zip\">zip</a></li>"))
		assertNull(releaseAsset(""))
	}

	// --------------------------------------------------------------------- notes

	private val feed = """
		<?xml version="1.0" encoding="UTF-8"?>
		<feed xmlns="http://www.w3.org/2005/Atom">
		  <title>Release notes from dsh-tabs-android</title>
		  <entry>
		    <id>tag:github.com,2008:Repository/1401987418/v0.2.5</id>
		    <link rel="alternate" type="text/html" href="https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5"/>
		    <title>dsh-tabs for Android 0.2.5</title>
		    <content type="html">&lt;p&gt;What is new&lt;/p&gt;&lt;ul&gt;&lt;li&gt;Fallback reading&lt;/li&gt;&lt;/ul&gt;</content>
		  </entry>
		  <entry>
		    <id>tag:github.com,2008:Repository/1401987418/v0.2.4</id>
		    <link rel="alternate" type="text/html" href="https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.4"/>
		    <title>dsh-tabs for Android 0.2.4</title>
		    <content type="html">&lt;p&gt;What was new before&lt;/p&gt;</content>
		  </entry>
		</feed>
	""".trimIndent()

	@Test
	fun `the feed gives the notes of the tag asked for, as text`() {
		assertEquals("What is new\n\nFallback reading", releaseNotes(feed, "v0.2.5"))
	}

	@Test
	fun `a br and the feed's own newline are one line break`() {
		val broken = """
			<feed><entry>
			  <link href="https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5"/>
			  <content type="html">&lt;p&gt;One&lt;br&gt;
Two&lt;/p&gt;</content>
			</entry></feed>
		""".trimIndent()
		assertEquals("One\nTwo", releaseNotes(broken, "v0.2.5"))
	}

	@Test
	fun `a tag is matched whole, not as a prefix`() {
		val only = """
			<feed><entry>
			  <link href="https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.50"/>
			  <content type="html">&lt;p&gt;Later&lt;/p&gt;</content>
			</entry></feed>
		""".trimIndent()
		assertNull(releaseNotes(only, "v0.2.5"))
	}

	@Test
	fun `entities are decoded exactly once`() {
		val escaped = """
			<feed><entry>
			  <link href="https://github.com/OahzOb/dsh-tabs-android/releases/tag/v0.2.5"/>
			  <content type="html">&lt;p&gt;A &amp;amp; B&lt;/p&gt;</content>
			</entry></feed>
		""".trimIndent()
		assertEquals("A & B", releaseNotes(escaped, "v0.2.5"))
	}

	@Test
	fun `a feed without the entry has no notes`() {
		assertNull(releaseNotes(feed, "v0.9.9"))
		assertNull(releaseNotes("", "v0.2.5"))
	}

	// ----------------------------------------------------------------------- api

	private val api = """
		{
		  "tag_name": "v0.2.6",
		  "body": "What is new",
		  "assets": [
		    {
		      "name": "source.tar.gz",
		      "browser_download_url": "https://example.invalid/source.tar.gz"
		    },
		    {
		      "name": "dsh-tabs-android-0.2.6.apk",
		      "browser_download_url": "https://github.com/OahzOb/dsh-tabs-android/releases/download/v0.2.6/dsh-tabs-android-0.2.6.apk",
		      "digest": "sha256:4e19c5c9d55563a307e6c5ba0f18e755625bc556f604c7c731a36ab44e410c60",
		      "size": 18443972
		    }
		  ]
		}
	""".trimIndent()

	@Test
	fun `the api body decodes into the offer the dialog needs`() {
		val result = readRelease(api, "0.2.5")
		assertTrue(result is Updates.Check.Newer)
		val update = (result as Updates.Check.Newer).update
		assertEquals("0.2.6", update.version)
		assertEquals("What is new", update.notes)
		assertEquals(
			"https://github.com/OahzOb/dsh-tabs-android/releases/download/v0.2.6/dsh-tabs-android-0.2.6.apk",
			update.url
		)
		assertEquals("4e19c5c9d55563a307e6c5ba0f18e755625bc556f604c7c731a36ab44e410c60", update.sha256)
		assertEquals(18_443_972L, update.size)
	}

	@Test
	fun `a tag at or below this build is current`() {
		assertEquals(Updates.Check.Current, readRelease(api, "0.2.6"))
		assertEquals(Updates.Check.Current, readRelease(api, "0.9.9"))
	}

	@Test
	fun `a body that does not name an apk is a failure`() {
		val noAssets = """{"tag_name": "v0.2.6", "assets": []}"""
		assertTrue(readRelease(noAssets, "0.2.5") is Updates.Check.Failed)
		val noTag = """{"assets": []}"""
		assertTrue(readRelease(noTag, "0.2.5") is Updates.Check.Failed)
		assertTrue(readRelease("not json at all", "0.2.5") is Updates.Check.Failed)
	}

	@Test
	fun `a json null is not a version and not a note`() {
		val nullTag = """{"tag_name": null, "assets": []}"""
		assertTrue(readRelease(nullTag, "0.2.5") is Updates.Check.Failed)
		val nullBody = """{"tag_name": "v0.2.6", "body": null, "assets": [
			{"name": "app.apk", "browser_download_url": "https://example.invalid/app.apk"}
		]}"""
		val update = (readRelease(nullBody, "0.2.5") as Updates.Check.Newer).update
		assertEquals("", update.notes)
	}

	@Test
	fun `a digest that is not a sha256 counts as none`() {
		fun digestOf(value: String): String? {
			val body = """{"tag_name": "v0.2.6", "assets": [
				{"name": "app.apk", "browser_download_url": "https://example.invalid/app.apk", "digest": $value}
			]}"""
			return (readRelease(body, "0.2.5") as Updates.Check.Newer).update.sha256
		}
		// Android's org.json and the JVM's disagree about optString on a JSON null;
		// neither may smuggle "null" into the download's comparison.
		assertNull(digestOf("null"))
		assertNull(digestOf("\"null\""))
		assertNull(digestOf("\"sha256:abc\""))
		assertNull(digestOf("\"sha512:" + "a".repeat(64) + "\""))
		assertEquals(
			"4e19c5c9d55563a307e6c5ba0f18e755625bc556f604c7c731a36ab44e410c60",
			digestOf("\"sha256:4E19C5C9D55563A307E6C5BA0F18E755625BC556F604C7C731A36AB44E410C60\"")
		)
	}

	// ------------------------------------------------------------------- failure

	@Test
	fun `a spent quota is reported as a spent quota, with the address GitHub saw`() {
		val reason = describeApiFailure(
			status = 403,
			message = "API rate limit exceeded for 203.0.113.7. (But here's the good news: " +
				"Authenticated requests get a higher rate limit. Check out the documentation for more details.)",
			remaining = "0",
			reset = "1795655084"
		)
		assertTrue(reason, reason.startsWith("GitHub answered HTTP 403"))
		assertTrue(reason, reason.contains("API rate limit exceeded for 203.0.113.7"))
		assertTrue(reason, reason.contains("unauthenticated quota"))
		assertTrue(reason, Regex("resets at \\d\\d:\\d\\d").containsMatchIn(reason))
		assertTrue(reason, !reason.contains("good news"))
	}

	@Test
	fun `a quota that is not spent keeps its own message and no reset`() {
		val reason = describeApiFailure(403, "Forbidden thing", "12", "1795655084")
		assertEquals("GitHub answered HTTP 403 (Forbidden thing)", reason)
		assertEquals("GitHub answered HTTP 500", describeApiFailure(500, "", null, null))
	}
}
