package dev.dshtabs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
 * after three checks — the asset's SHA-256, the APK's package name and version code,
 * and the signing certificate against the installed app's. The OS enforces the last
 * one anyway, but doing it here is what turns a bad release into an error message
 * instead of a system dialog that ends in `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
 *
 * **The check is a GET with no credentials and no identifiers.** It reveals this
 * device's address to GitHub, which is the same thing any browser does; nothing about
 * the device, the book or the sessions is sent. GitHub's unauthenticated limit is 60
 * requests an hour, and the app asks at most once a day plus when the operator taps.
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

	private const val RELEASE_API = "https://api.github.com/repos/OahzOb/dsh-tabs-android/releases/latest"
	private const val USER_AGENT = "dsh-tabs-android-updater"
	private const val CONNECT_TIMEOUT_MS = 15_000
	private const val READ_TIMEOUT_MS = 30_000
	private const val BUFFER_BYTES = 16 * 1024

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
	 * @returns the newer release, `Current`, or the reason it could not be decided.
	 */
	suspend fun check(): Check = withContext(Dispatchers.IO) {
		var connection: HttpURLConnection? = null
		try {
			connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				setRequestProperty("User-Agent", "$USER_AGENT/${BuildConfig.VERSION_NAME}")
				setRequestProperty("Accept", "application/vnd.github+json")
				connectTimeout = CONNECT_TIMEOUT_MS
				readTimeout = READ_TIMEOUT_MS
				instanceFollowRedirects = true
			}
			val status = connection.responseCode
			if (status != HttpURLConnection.HTTP_OK) {
				return@withContext Check.Failed("GitHub answered HTTP $status")
			}
			val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			val release = JSONObject(body)
			val version = release.optString("tag_name").removePrefix("v").trim()
			if (version.isEmpty()) return@withContext Check.Failed("the release has no tag")
			if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return@withContext Check.Current
			val assets = release.optJSONArray("assets") ?: return@withContext Check.Failed("the release has no assets")
			var apk: JSONObject? = null
			for (index in 0 until assets.length()) {
				val asset = assets.optJSONObject(index) ?: continue
				if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
					apk = asset
					break
				}
			}
			val asset = apk ?: return@withContext Check.Failed("the release has no APK")
			val url = asset.optString("browser_download_url")
			if (url.isEmpty()) return@withContext Check.Failed("the APK has no download URL")
			Check.Newer(
				Available(
					version = version,
					notes = release.optString("body").trim(),
					url = url,
					sha256 = asset.optString("digest").removePrefix("sha256:").trim().ifEmpty { null },
					size = asset.optLong("size", 0L)
				)
			)
		} catch (error: Exception) {
			Check.Failed(error.message ?: error.javaClass.simpleName)
		} finally {
			connection?.disconnect()
		}
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
			connection = (URL(update.url).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				setRequestProperty("User-Agent", "$USER_AGENT/${BuildConfig.VERSION_NAME}")
				connectTimeout = CONNECT_TIMEOUT_MS
				readTimeout = READ_TIMEOUT_MS
				instanceFollowRedirects = true
			}
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
