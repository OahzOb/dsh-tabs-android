package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version comparison the update check decides "newer" with.
 *
 * This is the one part of the updater that can be settled without a network or a
 * device, and it is worth pinning: a plain string comparison puts `0.10.0` *below*
 * `0.9.0`, and a wrong answer here either hides an update for ever or offers the
 * version already installed.
 */
class UpdateVersionTest {

	@Test
	fun `a later major, minor or patch is newer`() {
		assertTrue(compareVersions("0.2.0", "0.1.9") > 0)
		assertTrue(compareVersions("0.2.1", "0.2.0") > 0)
		assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
	}

	@Test
	fun `numeric parts are numbers, not strings`() {
		assertTrue("0.10.0 must be newer than 0.9.0", compareVersions("0.10.0", "0.9.0") > 0)
		assertTrue(compareVersions("0.9.0", "0.10.0") < 0)
	}

	@Test
	fun `the tag prefix and an equal version compare equal`() {
		assertEquals(0, compareVersions("v0.2.0", "0.2.0"))
		assertEquals(0, compareVersions("0.2", "0.2.0"))
		assertEquals(0, compareVersions("0.2.0-rc.1", "0.2.0"))
	}
}
