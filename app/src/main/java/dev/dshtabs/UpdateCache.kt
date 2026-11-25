package dev.dshtabs

/**
 * The update cache's naming rule, which is the only index it has.
 *
 * A downloaded APK is kept in `cache/updates` until it is installed, and **that is what
 * makes an uninstalled download reusable**: a check derives the file name from the
 * release tag, and the next check derives the same name and finds the file — so
 * tapping Download twice, or dismissing the system installer and checking again, no
 * longer fetches the whole APK again.
 *
 * The cleanup reads the version back out of the same name. Both halves are pure and
 * live here rather than inline in [Updates] because a round trip that silently stops
 * matching has two quiet failures: the cached APK is never found (every check
 * re-downloads), or the cleanup deletes a release that is still being offered.
 */

/** The prefix every entry this app owns carries. */
private const val PREFIX = "dsh-tabs-android-"

/** The suffix the updater's server-side picker already requires of an asset. */
private const val SUFFIX = ".apk"

/**
 * The cache file name for one release's APK.
 * @param version - the release version, as its tag gave it (no `v`).
 * @returns the file name inside the update cache directory.
 */
internal fun updateCacheName(version: String): String = "$PREFIX$version$SUFFIX"

/**
 * The version a cache entry belongs to.
 * @param fileName - one entry of the update cache directory.
 * @returns the version, or null for anything this app did not put there.
 */
internal fun updateCacheVersion(fileName: String): String? {
	if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null
	return fileName.removePrefix(PREFIX).removeSuffix(SUFFIX).ifEmpty { null }
}

/**
 * Whether one cache entry is obsolete once the newest useful version is [atOrBelow].
 *
 * Android refuses an APK whose `versionCode` is not newer than the installed one, so a
 * cached release at or below the installed version can never be installed; and a
 * cached release at or below the version a fresh check just downloaded has been
 * superseded by that download. Anything this app did not name is left alone.
 *
 * @param fileName - one entry of the update cache directory.
 * @param atOrBelow - the version that now covers everything older.
 * @returns true when the entry should be deleted.
 */
internal fun updateCacheIsObsolete(fileName: String, atOrBelow: String): Boolean {
	val version = updateCacheVersion(fileName) ?: return false
	return compareVersions(version, atOrBelow) <= 0
}
