package dev.dshtabs

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One configured machine.
 *
 * The field names match `$DSH_HOME/dsh-tabs.json` on the desktop side exactly, so
 * a book can be moved between the two clients by hand. That is the whole reason
 * this is a flat data class rather than something room-backed: the format is a
 * contract with another program, not an implementation detail.
 *
 * There is no `local` device and no `kind` field. The desktop app has a local tab
 * because it can start a Harness on the machine it runs on; a phone client cannot
 * and deliberately does not — see the README. Every entry here is a remote.
 */
data class Device(
	val id: String,
	val label: String,
	val host: String,
	val user: String,
	val sshPort: Int = Remote.DEFAULT_SSH_PORT,
	/** Launch directory for the remote Harness, `~`-relative or absolute. */
	val directory: String? = null,
	/** Cached shell family, so the probe costs one round trip per machine, not per connect. */
	val platform: Remote.Platform? = null,
	/**
	 * The host key this machine presented the first time, base64 of the SSH wire
	 * format. Trust on first use, then pinning — see [HostKeys] for why JSch's own
	 * `StrictHostKeyChecking` cannot be used for this.
	 */
	val hostKey: String? = null
) {
	/** What the tab bar shows. */
	val display: String get() = label.ifBlank { host }

	companion object {
		/** A new device, with an id that is stable across edits. */
		fun create(label: String, host: String, user: String, sshPort: Int, directory: String?): Device =
			Device(
				id = "dev-${UUID.randomUUID().toString().take(8)}",
				label = label.ifBlank { host },
				host = host,
				user = user,
				sshPort = sshPort,
				directory = directory?.takeIf { it.isNotBlank() }
			)

		fun fromJson(json: JSONObject): Device = Device(
			id = json.optString("id").ifBlank { "dev-${UUID.randomUUID().toString().take(8)}" },
			label = json.optString("label"),
			host = json.optString("host"),
			user = json.optString("user"),
			sshPort = json.optInt("sshPort", Remote.DEFAULT_SSH_PORT).takeIf { it > 0 } ?: Remote.DEFAULT_SSH_PORT,
			directory = json.optString("directory").takeIf { it.isNotBlank() },
			platform = when (json.optString("platform")) {
				"posix" -> Remote.Platform.POSIX
				"windows" -> Remote.Platform.WINDOWS
				else -> null
			},
			hostKey = json.optString("hostKey").takeIf { it.isNotBlank() }
		)
	}

	fun toJson(): JSONObject = JSONObject().apply {
		put("id", id)
		put("label", label)
		put("transport", "ssh")
		put("host", host)
		put("user", user)
		put("sshPort", sshPort)
		directory?.let { put("directory", it) }
		platform?.let { put("platform", if (it == Remote.Platform.POSIX) "posix" else "windows") }
		hostKey?.let { put("hostKey", it) }
	}
}

/**
 * The device book, in the app's private files directory.
 *
 * The desktop app writes `$DSH_HOME/dsh-tabs.json` and the plugin writes
 * `remote-devices.json`; this is a third file with the same schema. It has to be
 * separate for the same reason those two are: two writers of one JSON file clobber
 * each other, and a phone is a different machine anyway.
 *
 * Every read-modify-write goes through [mutate], which serializes them. The desktop
 * side learned that the hard way — a plain load-then-save lost updates whenever a
 * connection finished while the editor was open, and could put back a device that
 * had just been removed.
 */
object DeviceBook {
	private const val FILE = "dsh-tabs.json"

	@Volatile
	private var file: java.io.File? = null

	private val lock = Any()

	fun init(filesDir: java.io.File) {
		synchronized(lock) {
			file = java.io.File(filesDir, FILE)
		}
	}

	private fun requireFile(): java.io.File =
		file ?: error("DeviceBook.init() has not been called")

	fun load(): List<Device> {
		val target = requireFile()
		if (!target.exists()) return emptyList()
		return try {
			val root = JSONObject(target.readText())
			val array = root.optJSONArray("devices") ?: JSONArray()
			(0 until array.length()).mapNotNull { index ->
				array.optJSONObject(index)?.let { Device.fromJson(it) }
			}
		} catch (error: Exception) {
			// A damaged book is not a reason to fail: the operator can re-add a
			// machine, but a crash on launch is not recoverable from inside the app.
			emptyList()
		}
	}

	fun save(devices: List<Device>) {
		val target = requireFile()
		val root = JSONObject().apply {
			put("devices", JSONArray().apply { devices.forEach { put(it.toJson()) } })
		}
		target.parentFile?.mkdirs()
		// Write beside the target and rename, so a process death mid-write cannot
		// leave a half-written book behind.
		val temporary = java.io.File(target.parentFile, "$FILE.tmp")
		temporary.writeText(root.toString(2))
		if (!temporary.renameTo(target)) {
			target.writeText(root.toString(2))
			temporary.delete()
		}
	}

	/**
	 * Change the book by read-modify-write, one caller at a time.
	 *
	 * @param change receives the current devices; return the new list, or null to
	 *   write nothing — which is how a caller whose device has since been removed
	 *   declines to write at all.
	 */
	@Synchronized
	fun mutate(change: (List<Device>) -> List<Device>?): List<Device>? {
		val stored = load()
		val updated = change(stored) ?: return null
		save(updated)
		return updated
	}
}
