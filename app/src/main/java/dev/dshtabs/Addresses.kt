package dev.dshtabs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Address choice for one machine: LAN first in auto mode, or the host the operator
 * picked by hand.
 *
 * The device book keeps only one [Device.host] (the primary address, which old
 * clients understand) plus extra [Device.addresses]; this object turns that into
 * an ordered list and a single host to connect to. It is deliberately pure apart
 * from the injectable [probe], so the decision can be checked on the build machine
 * without a network — see `AddressesTest`.
 */
object Addresses {
	/** The probe budget. Long enough for a real LAN answer, short enough to try several. */
	private const val PROBE_TIMEOUT_MS = 1_200

	/**
	 * What one address decision says.
	 *
	 * @param host the address to actually connect to.
	 * @param label where it came from: the record's label, `LAN`, `other`, `selected`
	 *   or `fallback`. This is a hint for the log and the dialog, never a routing rule.
	 * @param tried hosts probed in order, including a successful one; empty for a
	 *   hand-picked address, which is not probed at all.
	 * @param fromProbe true only when a TCP probe answered for [host].
	 * @param message a human explanation when a choice had to be ignored or every
	 *   address failed — it names the addresses that were tried.
	 */
	data class Resolution(
		val host: String,
		val label: String?,
		val tried: List<String> = emptyList(),
		val fromProbe: Boolean = false,
		val message: String? = null
	)

	/**
	 * The primary address followed by the extras, without blanks or duplicates.
	 *
	 * Comparison is case-insensitive so `BOX-A` and `box-a` count as one address;
	 * the first spelling wins, which keeps the primary host stable.
	 */
	fun candidates(device: Device): List<DeviceAddress> {
		val seen = HashSet<String>()
		val result = mutableListOf<DeviceAddress>()
		fun add(entry: DeviceAddress) {
			val host = entry.host.trim()
			if (host.isEmpty() || !seen.add(host.lowercase())) return
			result += DeviceAddress(host, entry.label?.takeIf { it.isNotBlank() })
		}
		add(DeviceAddress(device.host, null))
		device.addresses.forEach(::add)
		return result
	}

	/**
	 * True for a private IPv4 or IPv6 literal that is ordinarily reachable without
	 * leaving the building: 10/8, 172.16/12, 192.168/16, 169.254/16,
	 * fc00::/7, fe80::/10.
	 *
	 * Tailscale's 100.64/10 is deliberately **not** LAN — it is a fallback, and
	 * treating it as local would connect to it first. Names are never guessed at:
	 * a hostname is neither LAN nor non-LAN, so auto mode probes it in its recorded
	 * order. No DNS lookup happens here, so this is safe to call on the UI thread.
	 */
	fun isLan(host: String): Boolean {
		val bare = host.trim().trim('[', ']').substringBefore('%')
		val octets = bare.split('.')
		if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
			val numbers = octets.map { it.toInt() }
			val (a, b) = numbers[0] to numbers[1]
			return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
				(a == 169 && b == 254)
		}
		val first = firstHextet(bare) ?: return false
		// fc00::/7 — fc00:… and fd00:…; fe80::/10 — fe80:… through febf:….
		return (first and 0xFE00) == 0xFC00 || (first and 0xFFC0) == 0xFE80
	}

	/** The first 16-bit group of an IPv6 literal, or null for anything else. */
	private fun firstHextet(host: String): Int? {
		if (host.count { it == ':' } < 2) return null
		if (host.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' }) return null
		val first = if (host.startsWith("::")) "0" else host.substringBefore(':')
		if (first.isEmpty() || first.length > 4) return null
		return first.toInt(16)
	}

	/**
	 * Parse the dialog's one-address-per-line field. `label=host` sets a label;
	 * a bare line is just a host. The first entry becomes the primary host.
	 */
	fun parse(lines: String): List<DeviceAddress> = lines
		.lines()
		.map { it.trim() }
		.filter { it.isNotEmpty() }
		.map { line ->
			val split = line.indexOf('=')
			if (split < 0) DeviceAddress(line, null)
			else DeviceAddress(
				host = line.substring(split + 1).trim(),
				label = line.substring(0, split).trim().takeIf { it.isNotEmpty() }
			)
		}

	/**
	 * Choose the host to connect to.
	 *
	 * A hand-picked [Device.addressChoice] wins without a probe: the operator asked
	 * for that address, and remembering it is the whole point. If the record no
	 * longer contains it, the primary host is used and the reason is reported.
	 * In auto mode LAN addresses are probed first, then the others, each on
	 * `device.sshPort`; the first one that answers wins. Nothing answering falls
	 * back to the primary host with a message naming every address tried.
	 *
	 * @param probe injected for tests; the default opens a short TCP connection.
	 */
	suspend fun resolve(
		device: Device,
		probe: suspend (host: String, port: Int) -> Boolean = ::tcpConnect
	): Resolution = withContext(Dispatchers.IO) {
		ensureActive()
		val all = candidates(device)
		val primary = all.firstOrNull() ?: return@withContext Resolution(
			host = device.host,
			label = "fallback",
			message = "this machine has no address configured"
		)
		val choice = device.addressChoice
		// One address means there is no choice to make. The desktop client skips the
		// probe here too, so an ordinary connect does not pay a timeout for nothing;
		// ssh itself gives the precise failure if the machine is unreachable.
		if (choice == Device.AUTO && all.size == 1) {
			val only = all.first()
			return@withContext Resolution(
				host = only.host,
				label = only.label ?: if (isLan(only.host)) "LAN" else "other"
			)
		}
		if (choice != Device.AUTO) {
			val selected = all.firstOrNull { it.host.equals(choice, ignoreCase = true) }
			if (selected != null) {
				return@withContext Resolution(
					host = selected.host,
					label = selected.label ?: "selected"
				)
			}
			return@withContext Resolution(
				host = primary.host,
				label = "fallback",
				message = "the remembered address $choice is no longer in the book; " +
					"using the primary host ${primary.host}"
			)
		}

		val ordered = all.sortedWith(compareBy { !isLan(it.host) })
		val tried = mutableListOf<String>()
		for (address in ordered) {
			ensureActive()
			tried += address.host
			if (probe(address.host, device.sshPort)) {
				val source = address.label ?: if (isLan(address.host)) "LAN" else "other"
				return@withContext Resolution(
					host = address.host,
					label = source,
					tried = tried.toList(),
					fromProbe = true
				)
			}
		}
		Resolution(
			host = primary.host,
			label = "fallback",
			tried = tried.toList(),
			message = "no address answered on port ${device.sshPort} " +
				"(tried: ${tried.joinToString(", ")}); using the primary host ${primary.host}"
		)
	}

	/** A real, short TCP connect. Bracketed IPv6 literals are accepted as well. */
	private suspend fun tcpConnect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
		ensureActive()
		val bare = host.trim().trim('[', ']')
		var socket: Socket? = null
		try {
			socket = Socket()
			socket.connect(InetSocketAddress(bare, port), PROBE_TIMEOUT_MS)
			ensureActive()
			true
		} catch (cancelled: CancellationException) {
			// A cancelled connect is not an unreachable address: it must reach the
			// caller so it can stop the work instead of trying the next host.
			throw cancelled
		} catch (_: Exception) {
			false
		} finally {
			try {
				socket?.close()
			} catch (_: Exception) {
				/* nothing left to release */
			}
		}
	}
}
