package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership arithmetic behind a session that outlives its window.
 *
 * `SessionRegistry` is the one piece of that change a JVM test can actually exercise:
 * it is pure Kotlin over a `LinkedHashMap`, with no Android and no socket in it. The
 * rest — an activity destroyed and another created, a view rebuilt, a foreground
 * service kept — needs a device, which is why the wiring is checked on the tablet and
 * not here.
 *
 * What matters and is easy to get wrong is that the registry **never stops anything**.
 * Every give-up returns the value so exactly one caller can stop it: a registry that
 * stopped things itself would stop a session `MainActivity` still had a tab for, and a
 * caller that assumed `remove` had done it would leak an SSH connection. These tests
 * pin both halves of that contract.
 */
class SessionRegistryTest {

	@Test
	fun `adopt keeps the value and hands back whatever it displaced`() {
		val registry = SessionRegistry<String>()
		assertNull("an empty registry adopted nothing", registry.adopt("dev", "first"))

		val displaced = registry.adopt("dev", "second")
		assertEquals(
			"adopt replaced an existing value without returning it, so the displaced " +
				"session — an SSH connection, a forward and a dsh web — has no owner left " +
				"to stop it",
			"first",
			displaced
		)
		assertEquals("second", registry.get("dev"))
	}

	@Test
	fun `remove gives the value up rather than stopping it`() {
		val registry = SessionRegistry<String>()
		registry.adopt("dev", "session")

		val removed = registry.remove("dev")
		assertEquals(
			"remove did not return the session, so the caller cannot stop it and the " +
				"connection leaks with no reference anywhere",
			"session",
			removed
		)
		assertNull("remove left the entry behind", registry.get("dev"))
		assertNull("removing a session twice returned it twice", registry.remove("dev"))
	}

	@Test
	fun `clear gives every value up and empties the registry`() {
		val registry = SessionRegistry<String>()
		registry.adopt("a", "one")
		registry.adopt("b", "two")

		val cleared = registry.clear().toSet()
		assertEquals(setOf("one", "two"), cleared)
		assertTrue("clear left entries behind", registry.isEmpty())
		assertTrue("a second clear returned something", registry.clear().isEmpty())
	}

	@Test
	fun `a snapshot is a copy, so a caller can iterate while the registry changes`() {
		val registry = SessionRegistry<String>()
		registry.adopt("a", "one")

		val snapshot = registry.snapshot()
		registry.adopt("b", "two")

		assertEquals("the snapshot changed after it was taken", 1, snapshot.size)
		assertEquals(2, registry.snapshot().size)
	}

	@Test
	fun `a window is told when a session it did not open arrives`() {
		val registry = SessionRegistry<String>()
		var calls = 0
		val observer: () -> Unit = { calls++ }
		registry.observe(observer)

		registry.adopt("a", "one")
		assertEquals(
			"an adopted session did not notify, so a window created before the handover " +
				"would keep drawing CONNECT over a live connection",
			1,
			calls
		)

		registry.get("a")
		registry.isEmpty()
		assertEquals("a read notified the window", 1, calls)

		registry.remove("a")
		assertEquals("a removed session did not notify", 2, calls)

		registry.unobserve(observer)
		registry.adopt("b", "two")
		assertEquals("an unobserved registry still notified", 2, calls)
	}

	@Test
	fun `an outgoing window cannot clear the incoming window's observer`() {
		val registry = SessionRegistry<String>()
		var outgoing = 0
		var incoming = 0
		val first: () -> Unit = { outgoing++ }
		val second: () -> Unit = { incoming++ }

		registry.observe(first)
		// The OEM-relaunch order that makes this matter: the new activity registers
		// before the old one is destroyed.
		registry.observe(second)
		registry.unobserve(first)

		registry.adopt("dev", "session")

		assertEquals("the incoming window was not notified", 1, incoming)
		assertEquals(
			"the outgoing window cleared the incoming window's observer, so a session " +
				"that survives a relaunch would arrive at no window at all",
			0,
			outgoing
		)
	}

	@Test
	fun `the displaced value is the same object the caller held`() {
		val registry = SessionRegistry<Any>()
		val first = Any()
		val second = Any()
		registry.adopt("dev", first)
		assertSame(first, registry.adopt("dev", second))
		assertSame(second, registry.get("dev"))
		assertFalse(registry.isEmpty())
	}
}
