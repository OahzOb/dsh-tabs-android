package dev.dshtabs

import java.io.File

/**
 * Append-only, self-trimming text file, for a log that has to survive the process.
 *
 * The app has no logger on purpose — a phone client that writes to logcat is writing
 * to a buffer that rotates in minutes, and the failures this file exists for are
 * noticed after the fact: a connection that ended while the app was in the background,
 * a window that was recreated, a session that was torn down by a task removal. By the
 * time the operator can say "it disconnected", the transcript panel is gone with the
 * process.
 *
 * So the record goes to internal storage instead, and it **trims itself** rather than
 * growing until somebody notices: once the file passes [maxBytes], the newest lines
 * that fit in [keepBytes] are kept and the rest are dropped. Trimming is a rewrite of
 * at most a few tens of kilobytes, so it does not need a background thread.
 *
 * Everything here is best-effort. A diagnostics file that throws is worse than no
 * diagnostics file: the caller is a lifecycle callback, and losing the record is
 * acceptable where crashing the app is not.
 *
 * @param target - the file to append to; its parent is created if missing.
 * @param maxBytes - the size at which the file is trimmed.
 * @param keepBytes - how much of the newest end survives a trim.
 */
internal class DiagFile(
	private val target: File,
	private val maxBytes: Int = 64 * 1024,
	private val keepBytes: Int = 32 * 1024
) {

	private val lock = Any()

	/**
	 * Append one line, trimming first when the file has grown past its bound.
	 * @param line - the text to append; a newline is added.
	 */
	fun append(line: String) {
		synchronized(lock) {
			try {
				target.parentFile?.mkdirs()
				if (target.length() > maxBytes) trim()
				target.appendText("$line\n", Charsets.UTF_8)
			} catch (_: Throwable) {
				/* a record that cannot be written must not become the failure */
			}
		}
	}

	/**
	 * The newest lines, oldest first.
	 * @param maxLines - how many of the newest to return.
	 * @returns the lines, or an empty list when nothing has been written yet.
	 */
	fun tail(maxLines: Int): List<String> {
		synchronized(lock) {
			return try {
				if (!target.exists()) emptyList() else target.readLines(Charsets.UTF_8).takeLast(maxLines)
			} catch (_: Throwable) {
				emptyList()
			}
		}
	}

	/** Keep the newest bytes that fit, on a line boundary. Caller holds the lock. */
	private fun trim() {
		val lines = try {
			target.readLines(Charsets.UTF_8)
		} catch (_: Throwable) {
			return
		}
		val kept = ArrayList<String>()
		var bytes = 0
		for (line in lines.asReversed()) {
			// Measured in UTF-8 bytes, not characters: the bound is a file-size bound, and
			// an event line can carry a device name or a path that is not ASCII.
			val cost = line.toByteArray(Charsets.UTF_8).size + 1
			if (bytes + cost > keepBytes) break
			bytes += cost
			kept.add(line)
		}
		kept.reverse()
		val staging = File(target.parentFile, "${target.name}.trim")
		try {
			staging.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), Charsets.UTF_8)
			if (!staging.renameTo(target)) {
				target.delete()
				staging.renameTo(target)
			}
		} catch (_: Throwable) {
			/* leave the oversized file alone rather than lose it */
		}
	}
}
