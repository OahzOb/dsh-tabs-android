package dev.dshtabs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address decision, on the build machine: no device and no network.
 *
 * [Addresses.resolve] takes its probe as a parameter, so every branch — a manual
 * pick, LAN-first auto, a host that answers, and the all-failed fallback — can be
 * checked without opening a socket. The JSON cases matter just as much: a book
 * written before these fields existed has to load as one address in auto mode,
 * and a book written by the desktop client has to survive a round trip here.
 */
class AddressesTest {
	private fun device(
		host: String = "100.64.0.11",
		addresses: List<DeviceAddress> = emptyList(),
		choice: String = Device.AUTO
	) = Device(id = "dev-test", label = "box", host = host, user = "me", addresses = addresses, addressChoice = choice)

	@Test
	fun `LAN means private IPv4 or IPv6 literals, not Tailscale or a name`() {
		listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.1.1", "fc00::1", "fd12:3456::1", "FE80::1%eth0")
			.forEach { host ->
				assertTrue("$host is a private address", Addresses.isLan(host))
			}
		listOf(
			"100.64.0.11", // Tailscale's CGNAT range is a fallback, not LAN
			"172.32.0.1", "192.169.1.1", "8.8.8.8", "2001:db8::1",
			"box-a", "example.com", "999.1.1.1", ""
		).forEach { host ->
			assertFalse("$host is not a LAN address", Addresses.isLan(host))
		}
	}

	@Test
	fun `candidates keep the primary first and drop blanks and duplicates`() {
		val device = device(
			host = " 192.168.1.20 ",
			addresses = listOf(
				DeviceAddress("", "empty"),
				DeviceAddress("100.64.0.11", "Tailscale"),
				DeviceAddress("192.168.1.20", "duplicate of primary"),
				DeviceAddress("  fd00::2  ", "ULA"),
				DeviceAddress("100.64.0.11", "duplicate with different label")
			)
		)
		assertEquals(
			listOf(
				DeviceAddress("192.168.1.20", null),
				DeviceAddress("100.64.0.11", "Tailscale"),
				DeviceAddress("fd00::2", "ULA")
			),
			Addresses.candidates(device)
		)
	}

	@Test
	fun `a hand-picked address is used without probing and is remembered as picked`() = runBlocking {
		val device = device(
			host = "192.168.1.20",
			addresses = listOf(DeviceAddress("100.64.0.11", "Tailscale")),
			choice = "100.64.0.11"
		)
		var probes = 0
		val result = Addresses.resolve(device) { _, _ -> probes += 1; true }

		assertEquals("100.64.0.11", result.host)
		assertEquals("Tailscale", result.label)
		assertFalse(result.fromProbe)
		assertTrue(result.tried.isEmpty())
		assertNull(result.message)
		assertEquals("a picked address is never probed", 0, probes)
	}

	@Test
	fun `a single address is used without a probe`() = runBlocking {
		var probes = 0
		val result = Addresses.resolve(device()) { _, _ -> probes += 1; true }

		assertEquals("100.64.0.11", result.host)
		assertFalse(result.fromProbe)
		assertTrue(result.tried.isEmpty())
		assertEquals("there is no choice to make", 0, probes)
	}

	@Test
	fun `a remembered address that is no longer in the book falls back with a reason`() = runBlocking {
		val device = device(choice = "10.9.9.9")
		val result = Addresses.resolve(device) { _, _ -> true }

		assertEquals("100.64.0.11", result.host)
		assertTrue(result.message!!.contains("10.9.9.9"))
		assertTrue(result.message!!.contains("100.64.0.11"))
	}

	@Test
	fun `auto probes the LAN address first and stops at the one that answers`() = runBlocking {
		val device = device(
			addresses = listOf(
				DeviceAddress("100.64.0.11", "Tailscale"),
				DeviceAddress("192.168.1.20", null)
			)
		)
		val seen = mutableListOf<String>()
		val result = Addresses.resolve(device) { host, port ->
			seen += "$host:$port"
			host == "192.168.1.20"
		}

		assertEquals("192.168.1.20", result.host)
		assertEquals("LAN", result.label)
		assertTrue(result.fromProbe)
		assertEquals(listOf("192.168.1.20:22"), seen)
		assertEquals(listOf("192.168.1.20"), result.tried)
	}

	@Test
	fun `a labelled LAN address reports its label as the source`() = runBlocking {
		val device = device(
			host = "100.64.0.11",
			addresses = listOf(DeviceAddress("10.0.0.7", "Home LAN"))
		)
		val result = Addresses.resolve(device) { _, _ -> true }

		assertEquals("10.0.0.7", result.host)
		assertEquals("Home LAN", result.label)
	}

	@Test
	fun `auto tries every address on the SSH port and reports all of them when none answers`() = runBlocking {
		val device = device(
			host = "100.64.0.11",
			addresses = listOf(DeviceAddress("192.168.1.20", "LAN")),
			choice = Device.AUTO
		).copy(sshPort = 2222)
		val seen = mutableListOf<String>()
		val result = Addresses.resolve(device) { host, port ->
			seen += "$host:$port"
			false
		}

		assertEquals("the primary host is the fallback", "100.64.0.11", result.host)
		assertFalse(result.fromProbe)
		assertEquals(listOf("192.168.1.20:2222", "100.64.0.11:2222"), seen)
		assertEquals(listOf("192.168.1.20", "100.64.0.11"), result.tried)
		assertTrue(result.message!!.contains("192.168.1.20"))
		assertTrue(result.message!!.contains("100.64.0.11"))
		assertTrue(result.message!!.contains("2222"))
	}

	@Test
	fun `cancellation is not mistaken for an unreachable address`() = runBlocking {
		var probed = false
		val outcome = runCatching {
			Addresses.resolve(device(addresses = listOf(DeviceAddress("192.168.1.20", "LAN")))) { _, _ ->
				probed = true
				throw CancellationException("cancelled by the caller")
			}
		}
		assertTrue(outcome.exceptionOrNull() is CancellationException)
		assertTrue(probed)

		// Already cancelled before the call: no probe at all.
		val cancelled = Job().apply { cancel() }
		probed = false
		val outside = runCatching {
			withContext(cancelled) {
				Addresses.resolve(device(addresses = listOf(DeviceAddress("192.168.1.20", "LAN")))) { _, _ -> probed = true; true }
			}
		}
		assertTrue(outside.exceptionOrNull() is CancellationException)
		assertFalse(probed)
	}

	@Test
	fun `a line-based address field parses labels and keeps the first host primary`() {
		val parsed = Addresses.parse(
			"Home LAN=192.168.1.20\nTailscale=100.64.0.11\n\n  fd00::2  \n"
		)
		assertEquals(
			listOf(
				DeviceAddress("192.168.1.20", "Home LAN"),
				DeviceAddress("100.64.0.11", "Tailscale"),
				DeviceAddress("fd00::2", null)
			),
			parsed
		)
	}

	@Test
	fun `an old record with neither new field loads as one address in auto mode`() {
		val old = JSONObject(
			"""
			{"id":"dev-1","label":"box-a","transport":"ssh","host":"100.64.0.11",
			 "user":"operator","sshPort":22,"mode":"persistent","localPort":41001,
			 "hostKey":"key","platform":"posix"}
			""".trimIndent()
		)
		val loaded = Device.fromJson(old)

		assertTrue(loaded.addresses.isEmpty())
		assertEquals(Device.AUTO, loaded.addressChoice)
		assertEquals("100.64.0.11", loaded.host)
		assertEquals("key", loaded.hostKey)
		assertEquals(41001, loaded.localPort)
		assertTrue(loaded.persistent)
		assertEquals(Remote.Platform.POSIX, loaded.platform)

		val written = loaded.toJson()
		assertEquals(Device.AUTO, written.getString("addressChoice"))
		assertFalse("no extra addresses to write", written.has("addresses"))
		assertEquals(loaded, Device.fromJson(written))
	}

	@Test
	fun `extra addresses and a picked choice survive a JSON round trip`() {
		val original = device(
			host = "192.168.1.20",
			addresses = listOf(
				DeviceAddress("100.64.0.11", "Tailscale"),
				DeviceAddress("fd00::2", null)
			),
			choice = "100.64.0.11"
		)
		val written = original.toJson()

		assertEquals("192.168.1.20", written.getString("host"))
		assertEquals("100.64.0.11", written.getString("addressChoice"))
		val array = written.getJSONArray("addresses")
		assertEquals(2, array.length())
		assertEquals("Tailscale", array.getJSONObject(0).getString("label"))
		assertFalse("a null label is omitted", array.getJSONObject(1).has("label"))
		assertEquals(original, Device.fromJson(written))
	}
}
