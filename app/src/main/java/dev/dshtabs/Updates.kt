package dev.dshtabs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The sideload update channel: GitHub Releases, verified before the installer sees it.
 *
 * **There is no Play In-App Updates here, and there cannot be.** This app is signed
 * with its own key and installed from an APK, so the Play API has no package to
 * update. The replacement is a small client for the public release feed: one request
 * for the latest release, then a download that is only handed to the system installer
 * after three checks — the asset's SHA-256 when the release reports one, the APK's
 * package name and version code, and the signing certificate against the installed
 * app's. The OS enforces the last
 * one anyway, but doing it here is what turns a bad release into an error message
 * instead of a system dialog that ends in `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
 *
 * **The check is a GET with no credentials and no identifiers.** It reveals this
 * device's address to GitHub, which is the same thing any browser does; nothing about
 * the device, the book or the sessions is sent. GitHub's unauthenticated limit is 60
 * requests an hour **per address**, and the app asks at most once a day plus when the
 * operator taps — which is nothing until that address is shared, and a phone's often
 * is: carrier NAT, a shared office network, a proxy that sells one exit to many. So
 * when the API refuses with `403`, the same question is put to the release page,
 * which is served from a tier that quota does not cover. See [UpdateFeed] for what is
 * read there.
 *
 * The release is read from `releases/latest`, which excludes drafts and pre-releases;
 * the tag is compared as a dotted number, so `v0.2.0` is newer than `0.1.9` and
 * `0.10.0` is newer than `0.9.0`. The APK's `versionCode` is the second opinion: a
 * tag can be bumped without rebuilding, and that is exactly the mistake this check
 * should refuse rather than install.
 *
 * **A downloaded APK is kept until it is installed, and that is a feature.** The offer
 * asks the cache first, so a release that was downloaded and then dismissed at the
 * system installer is offered again as **Install** instead of being fetched a second
 * time; the file is deleted when a fresh download supersedes it, or when a check says
 * this build is already the latest. See [updateCacheName] for the naming rule that
 * makes the file findable.
 */
object Updates {

	/** The structured channel: one request, and where a size and digest are fields. */
	private const val RELEASE_API = "https://api.github.com/repos/$RELEASE_REPOSITORY/releases/latest"

	/** The browser channel's first page, which answers with a redirect to the tag. */
	private const val RELEASE_WEB = "https://github.com/$RELEASE_REPOSITORY/releases/latest"

	/** The fragment the release page loads to list its files. */
	private const val RELEASE_ASSETS = "https://github.com/$RELEASE_REPOSITORY/releases/expanded_assets/"

	/** The feed that carries every release's notes. */
	private const val RELEASE_FEED = "https://github.com/$RELEASE_REPOSITORY/releases.atom"

	private const val USER_AGENT = "dsh-tabs-android-updater"
	private const val CONNECT_TIMEOUT_MS = 15_000
	private const val READ_TIMEOUT_MS = 30_000

	/**
	 * How long the notes may delay an offer that is already decided.
	 *
	 * The notes are the only part of the browser channel that is optional — they make
	 * the dialog nicer to read and nothing else — so a feed that stalls must not hold
	 * the update while a 30-second read timeout runs out.
	 */
	private const val NOTES_READ_TIMEOUT_MS = 8_000

	private const val BUFFER_BYTES = 16 * 1024

	/** What a `/releases/latest` request is allowed to answer before the page itself. */
	private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

	/** One release that is newer than this build. */
	data class Available(
		val version: String,
		val notes: String,
		val url: String,
		/** `sha256:<hex>` from the release asset, or null when GitHub did not report one. */
		val sha256: String?,
		/** The asset's size in bytes, or 0 when it was not reported. */
		val size: Long
	)

	/** What one check found. */
	sealed interface Check {
		/** This build is the latest release. */
		data object Current : Check

		/** A newer release exists; nothing has been downloaded yet. */
		data class Newer(val update: Available) : Check

		/** The feed could not be read, or it did not name an APK. */
		data class Failed(val reason: String) : Check
	}

	/** What one download produced. */
	sealed interface Download {
		/** A verified APK in the cache, ready for the system installer. */
		data class Ready(val file: File) : Download

		/** The download or its verification failed; nothing is left on disk. */
		data class Failed(val reason: String) : Download
	}

	/**
	 * Ask GitHub for the latest release and compare it with this build.
	 *
	 * Two channels, one question. The API is asked first because it answers in one
	 * request with fields — notes, size, digest — that the pages carry only as prose;
	 * if it refuses, the release page is asked instead, because the refusal a phone
	 * actually meets (`403` from a shared address's spent quota) is one the page does
	 * not share. Only when both refuse is there a failure to report, and then both
	 * reasons are reported: a `403` and a timeout together say which side of the
	 * network broke.
	 *
	 * @returns the newer release, `Current`, or the reason it could not be decided.
	 */
	suspend fun check(): Check = withContext(Dispatchers.IO) {
		val api = checkApi()
		if (api !is Check.Failed) return@withContext api
		val web = checkWeb()
		if (web !is Check.Failed) {
			// A check that had to fall back is not a healthy check: the fact goes into
			// the shareable log, where a permanent API-channel regression will show up
			// instead of hiding behind a fallback that keeps working.
			Diag.log("update.check channel=web result=${web.summary()}")
			return@withContext web
		}
		Diag.log("update.check channel=none result=failed")
		Check.Failed("${api.reason}; ${web.reason}")
	}

	/** The result in three words, for one log line. */
	private fun Check.summary(): String = when (this) {
		is Check.Current -> "current"
		is Check.Newer -> "newer-${update.version}"
		is Check.Failed -> "failed"
	}

	/** One request to the release API, decoded. */
	private fun checkApi(): Check {
		var connection: HttpURLConnection? = null
		try {
			connection = open(RELEASE_API).apply {
				setRequestProperty("Accept", "application/vnd.github+json")
			}
			val status = connection.responseCode
			if (status != HttpURLConnection.HTTP_OK) return Check.Failed(apiRefusal(connection, status))
			val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			return readRelease(body, BuildConfig.VERSION_NAME)
		} catch (error: Exception) {
			return Check.Failed("the release API could not be read (${error.message ?: error.javaClass.simpleName})")
		} finally {
			connection?.disconnect()
		}
	}

	/** Why the API said no, with GitHub's own sentence and the quota's reset time. */
	private fun apiRefusal(connection: HttpURLConnection, status: Int): String {
		val body = try {
			connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
		} catch (_: Exception) {
			""
		}
		val message = try {
			JSONObject(body).optString("message")
		} catch (_: Exception) {
			""
		}
		return describeApiFailure(
			status = status,
			message = message,
			remaining = connection.getHeaderField("X-RateLimit-Remaining"),
			reset = connection.getHeaderField("X-RateLimit-Reset")
		)
	}

	/**
	 * The same question asked of the release page, the way a browser asks it: where
	 * `/releases/latest` redirects, then the asset list for that tag, then the feed
	 * for its notes. None of those three spends the API's quota.
	 */
	private fun checkWeb(): Check {
		var connection: HttpURLConnection? = null
		try {
			connection = open(RELEASE_WEB, followRedirects = false)
			val status = connection.responseCode
			val location = connection.getHeaderField("Location")
			connection.disconnect()
			connection = null
			if (status !in REDIRECT_STATUSES) {
				return Check.Failed("the release page answered HTTP $status instead of naming a tag")
			}
			val tag = releaseTag(location) ?: return Check.Failed("the release page named no tag")
			val version = tag.removePrefix("v").trim()
			if (version.isEmpty()) return Check.Failed("the release tag is empty")
			if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return Check.Current
			val asset = releaseAsset(readPage(RELEASE_ASSETS + tag))
				?: return Check.Failed("the release page named no APK")
			return Check.Newer(
				Available(
					version = version,
					notes = notesFor(tag).orEmpty(),
					url = asset.url,
					sha256 = asset.sha256,
					// The page prints a rounded size at best. The download reports
					// its own Content-Length through the progress callback, and only
					// a server that omits that leaves the bar indeterminate.
					size = 0L
				)
			)
		} catch (error: Exception) {
			return Check.Failed("the release page could not be read (${error.message ?: error.javaClass.simpleName})")
		} finally {
			connection?.disconnect()
		}
	}

	/**
	 * The feed's notes for one tag, or null: notes have never blocked an update.
	 *
	 * Its read timeout is shorter than the other requests' on purpose — see
	 * [NOTES_READ_TIMEOUT_MS].
	 */
	private fun notesFor(tag: String): String? = try {
		releaseNotes(readPage(RELEASE_FEED, NOTES_READ_TIMEOUT_MS), tag)
	} catch (_: Exception) {
		null
	}

	/**
	 * One page, fetched and read, or the exception that stopped it.
	 * @param url - where to ask.
	 * @param readTimeoutMs - how long a stalled body may hold the caller.
	 */
	private fun readPage(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String {
		var connection: HttpURLConnection? = null
		try {
			connection = open(url, readTimeoutMs = readTimeoutMs)
			val status = connection.responseCode
			if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
			return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
		} finally {
			connection?.disconnect()
		}
	}

	/**
	 * A GET carrying the updater's identity and timeouts.
	 * @param url - where to ask.
	 * @param followRedirects - whether the caller wants the answer or the redirect.
	 * @param readTimeoutMs - how long a stalled body may hold the caller.
	 * @returns the connection, not yet opened.
	 */
	private fun open(
		url: String,
		followRedirects: Boolean = true,
		readTimeoutMs: Int = READ_TIMEOUT_MS
	): HttpURLConnection =
		(URL(url).openConnection() as HttpURLConnection).apply {
			requestMethod = "GET"
			setRequestProperty("User-Agent", "$USER_AGENT/${BuildConfig.VERSION_NAME}")
			connectTimeout = CONNECT_TIMEOUT_MS
			readTimeout = readTimeoutMs
			instanceFollowRedirects = followRedirects
		}

	/**
	 * Download a release APK and verify it before it can be installed.
	 *
	 * @param context - the application context, for the cache and package manager.
	 * @param update - the release the check found.
	 * @param onProgress - percent complete, called from the IO dispatcher.
	 * @returns the verified file, or the reason it was refused.
	 */
	suspend fun download(
		context: Context,
		update: Available,
		onProgress: (Int) -> Unit
	): Download = withContext(Dispatchers.IO) {
		var connection: HttpURLConnection? = null
		val target = cachedFile(context, update.version)
		// **A download that was not installed is not a download that has to happen
		// again.** The cached file survives the process and the dialog: it is only gone
		// when the install completed (the next build prunes it) or a newer release
		// replaced it. Dismissing the system installer and checking again used to cost
		// the whole APK a second time, which on 18 MB is the difference between an
		// update people apply and one they postpone.
		reusable(context, target)?.let { return@withContext it }
		try {
			if (target.exists()) target.delete()
			connection = open(update.url)
			val status = connection.responseCode
			if (status != HttpURLConnection.HTTP_OK) {
				return@withContext Download.Failed("GitHub answered HTTP $status")
			}
			val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.size
			val digest = MessageDigest.getInstance("SHA-256")
			var copied = 0L
			connection.inputStream.use { input ->
				target.outputStream().buffered().use { output ->
					val buffer = ByteArray(BUFFER_BYTES)
					while (true) {
						val read = input.read(buffer)
						if (read < 0) break
						digest.update(buffer, 0, read)
						output.write(buffer, 0, read)
						copied += read
						if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
					}
				}
			}
			onProgress(100)
			val actual = digest.digest().joinToString("") { "%02x".format(it) }
			if (update.sha256 != null && !actual.equals(update.sha256, ignoreCase = true)) {
				target.delete()
				return@withContext Download.Failed("the APK's SHA-256 does not match the release")
			}
			val checked = verify(context, target)
			// A fresh download supersedes every older cached release, and Android will
			// not install one at or below the installed version anyway.
			if (checked is Download.Ready) prune(context, update.version, target)
			checked
		} catch (error: Exception) {
			target.delete()
			Download.Failed(error.message ?: error.javaClass.simpleName)
		} finally {
			connection?.disconnect()
		}
	}

	/**
	 * The cached APK for one release, when there is a valid one.
	 *
	 * The offer dialog asks this before drawing its button, so a release that is
	 * already on disk is offered as an **install** rather than as a download.
	 *
	 * @param context - the application context, for the cache.
	 * @param version - the release version.
	 * @returns the verified file, or null when nothing usable is cached.
	 */
	suspend fun cached(context: Context, version: String): Download.Ready? =
		withContext(Dispatchers.IO) { reusable(context, cachedFile(context, version)) }

	/**
	 * Delete cached APKs that [atOrBelow] supersedes.
	 *
	 * Called when a check says this build is the latest (everything at or below the
	 * installed version can never be installed) and after a fresh download (every
	 * older release has just been superseded). Anything this app did not name is left
	 * alone, so a foreign file in the cache directory is not the updater's business.
	 *
	 * @param context - the application context, for the cache.
	 * @param atOrBelow - the version that now covers everything older.
	 * @param keep - a file to leave alone even when the rule applies to it.
	 * @returns nothing.
	 */
	suspend fun prune(context: Context, atOrBelow: String, keep: File? = null): Unit = withContext(Dispatchers.IO) {
		File(context.cacheDir, "updates").listFiles()?.forEach { file ->
			if (file != keep && updateCacheIsObsolete(file.name, atOrBelow)) file.delete()
		}
	}

	/**
	 * Where one release's APK is cached until it is installed.
	 * @param context - the application context, for the cache directory.
	 * @param version - the release version.
	 * @returns the file, whether or not it exists yet.
	 */
	private fun cachedFile(context: Context, version: String): File =
		File(File(context.cacheDir, "updates").apply { mkdirs() }, updateCacheName(version))

	/**
	 * Verify a cached APK and hand it back when it is still installable.
	 *
	 * [verify] is the same check a fresh download goes through — package, newer
	 * `versionCode`, signing certificate — and it deletes the file when it fails, so a
	 * corrupted or foreign file is replaced rather than trusted.
	 *
	 * @param context - the application context.
	 * @param file - the cached file, which may not exist.
	 * @returns `Ready`, or null when there is nothing reusable.
	 */
	private fun reusable(context: Context, file: File): Download.Ready? {
		if (!file.exists()) return null
		return when (val checked = verify(context, file)) {
			is Download.Ready -> checked
			is Download.Failed -> null
		}
	}

	/**
	 * Check the downloaded APK's identity, version and signer.
	 *
	 * @param context - the application context.
	 * @param file - the downloaded APK.
	 * @returns `Ready`, or the reason it was refused; a refused file is deleted.
	 */
	private fun verify(context: Context, file: File): Download {
		val packageManager = context.packageManager
		val archive = packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
		if (archive == null) {
			file.delete()
			return Download.Failed("the download is not an APK")
		}
		if (archive.packageName != context.packageName) {
			file.delete()
			return Download.Failed("the APK is for ${archive.packageName}, not ${context.packageName}")
		}
		if (archive.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
			file.delete()
			return Download.Failed("the APK is version ${archive.longVersionCode}, which is not newer")
		}
		val downloaded = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
		val installed = packageManager
			.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
			.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
		if (downloaded.isEmpty() || installed.isEmpty()) {
			file.delete()
			return Download.Failed("the APK has no signing certificate to compare")
		}
		if (downloaded != installed) {
			file.delete()
			return Download.Failed("the APK is signed by a different key, so Android would refuse it")
		}
		return Download.Ready(file)
	}

	/** Whether Android will let this app open the package installer. */
	fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

	/**
	 * Hand a verified APK to the system installer.
	 *
	 * The URI is a [FileProvider] one: the cache is private to the app, and the
	 * installer is a different process. The grant is read-only and lasts for the
	 * started activity.
	 *
	 * @param context - an activity context, so the installer is not started from a
	 *   background process.
	 * @param file - the verified APK.
	 */
	fun install(context: Context, file: File) {
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
		val intent = Intent(Intent.ACTION_VIEW)
			.setDataAndType(uri, "application/vnd.android.package-archive")
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
	}
}
