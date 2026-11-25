package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update cache's naming rule, which is what makes an uninstalled download reusable.
 *
 * Two quiet failures are being pinned here. If the name stops round-tripping, the
 * cached APK is never found and every check downloads 18 MB again — the bug that
 * prompted this file. If the version cannot be read back out, the cleanup either stops
 * deleting anything or deletes a release that is still being offered.
 */
class UpdateCacheTest {

	@Test
	fun `a cache entry round-trips through its version`() {
		assertEquals("dsh-tabs-android-0.2.4.apk", updateCacheName("0.2.4"))
		assertEquals("0.2.4", updateCacheVersion(updateCacheName("0.2.4")))
		assertEquals("0.10.0", updateCacheVersion(updateCacheName("0.10.0")))
	}

	@Test
	fun `a file this app did not name belongs to nobody`() {
		assertNull(updateCacheVersion("dsh-tabs-android.apk"))
		assertNull(updateCacheVersion("something-else.apk"))
		assertNull(updateCacheVersion("dsh-tabs-android-0.2.4.zip"))
		assertNull(updateCacheVersion("dsh-tabs-android-0.2.4.apk.part"))
		// And the cleanup leaves those alone rather than deciding about them.
		assertFalse(updateCacheIsObsolete("something-else.apk", "9.9.9"))
		assertFalse(updateCacheIsObsolete("dsh-tabs-android-0.2.4.apk.part", "9.9.9"))
	}

	@Test
	fun `an entry at or below the covering version is obsolete`() {
		assertTrue(updateCacheIsObsolete("dsh-tabs-android-0.2.3.apk", "0.2.4"))
		assertTrue(updateCacheIsObsolete("dsh-tabs-android-0.2.4.apk", "0.2.4"))
		// Numeric, not string: 0.10.0 is newer than 0.9.0 and has to survive a 0.9.0
		// cleanup, while 0.2.9 is older than 0.10.0 and must not.
		assertFalse(updateCacheIsObsolete("dsh-tabs-android-0.10.0.apk", "0.9.0"))
		assertTrue(updateCacheIsObsolete("dsh-tabs-android-0.2.9.apk", "0.10.0"))
	}
}
