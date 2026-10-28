package dev.dshtabs

/**
 * Compare two dotted numeric versions, ignoring a `v` prefix and any pre-release
 * suffix.
 *
 * The updater compares the version in a GitHub tag with [BuildConfig.VERSION_NAME].
 * Both are simple `major.minor.patch` strings today, but a tag written as `v0.2.0`
 * is what GitHub shows and what the release notes use, so the prefix has to be
 * tolerated; parsing each part as a number is what makes `0.10.0` newer than
 * `0.9.0`, which string comparison gets backwards.
 *
 * @param a - one version.
 * @param b - the other.
 * @returns negative when `a` is older, positive when newer, zero when equal.
 */
internal fun compareVersions(a: String, b: String): Int {
	/** The numeric core of a version, as a list of numbers. */
	fun parts(value: String): List<Int> = value
		.trim()
		.removePrefix("v")
		.substringBefore('-')
		.split('.')
		.map { it.toIntOrNull() ?: 0 }

	val left = parts(a)
	val right = parts(b)
	for (index in 0 until maxOf(left.size, right.size)) {
		val difference = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
		if (difference != 0) return difference
	}
	return 0
}
