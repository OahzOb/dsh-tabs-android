package dev.dshtabs

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's own record of a connection's life, kept where a process death cannot take
 * it.
 *
 * ## Why this exists
 *
 * The Android client has no logger. Its failures are noticed *after* the fact — the
 * page reloaded, the tab went back to CONNECT, the operator's agent stopped mid-turn —
 * and by the time anybody can describe what happened, the only state that explained it
 * was in a process that no longer exists. The desktop app can be inspected with a
 * debugger attached; a phone in a split-screen window cannot.
 *
 * Measured, 2026-11-25: a session established at 21:04 ended some time before a new
 * one at 21:54, the app reconnected on its own, and the far side could say nothing
 * about **why** — sshd on Windows logs the accepted connection and not the end of it,
 * and the app wrote nothing anywhere. That report could only be answered with "it
 * reconnected". This file exists so the next one can be answered.
 *
 * ## What is written
 *
 * One line per event, timestamped, appended to `files/diag/connection.log` and trimmed
 * to the newest ~32 KB. The events are the decision points: window create/destroy with
 * the flags that choose handover over teardown, session adopt/stop, connect start/
 * result, the exec channel ending, each automatic retry, the foreground service's life,
 * network availability, the liveness probe, and the update check's channel when the API
 * refuses and the release page carries it. Nothing about the conversation, the
 * session content or the device book is written — only that a connection did something
 * at a time — so the file is safe to share, which is the other half of the design: it
 * is only useful if it can leave the device, and the release build cannot be read with
 * `run-as`.
 *
 * ## How it leaves the device
 *
 * Long-press the sync control in the tab bar; the log's tail goes to the share sheet.
 * [tail] is what the sheet carries.
 */
object Diag {

	private const val DIRECTORY = "diag"
	private const val FILE = "connection.log"

	/** How many of the newest lines the share sheet carries. */
	private const val SHARED_LINES = 400

	private val stamp: DateTimeFormatter =
		DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

	@Volatile
	private var file: DiagFile? = null

	/**
	 * Point the log at the app's private storage; called once from [App.onCreate].
	 * @param filesDir - the app's files directory.
	 */
	fun install(filesDir: File) {
		file = DiagFile(File(File(filesDir, DIRECTORY), FILE))
	}

	/**
	 * Record one event. Never throws and never blocks for long: the callers are
	 * lifecycle callbacks and connection state changes, and a diagnostics write that
	 * broke either would be worse than the gap it fills.
	 * @param event - what happened, in the form `area.what detail=value`.
	 */
	fun log(event: String) {
		file?.append("${stamp.format(Instant.now())} $event")
	}

	/**
	 * The newest recorded lines.
	 * @param maxLines - how many to return.
	 * @returns the lines, oldest first; empty when nothing has been recorded.
	 */
	fun tail(maxLines: Int = SHARED_LINES): List<String> = file?.tail(maxLines) ?: emptyList()

	/**
	 * A share intent carrying the log and the facts that make it interpretable.
	 *
	 * The log alone is not enough to read a report from a different device: the Android
	 * version, the build, and the manufacturer are what turn "the window was recreated"
	 * into a bug report. The version code is here because a release tag can be bumped
	 * without a rebuild, which is exactly the confusion the updater refuses.
	 *
	 * @param context - for the subject string.
	 * @returns an `ACTION_SEND` intent for `text/plain`.
	 */
	fun intent(context: Context): Intent {
		val header = buildString {
			appendLine("dsh-tabs ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
			appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
			appendLine("recorded oldest first; times are local")
			appendLine()
		}
		val lines = tail()
		val body = if (lines.isEmpty()) {
			"$header(no events recorded yet)"
		} else {
			header + lines.joinToString("\n")
		}
		return Intent(Intent.ACTION_SEND)
			.setType("text/plain")
			.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diag_subject, BuildConfig.VERSION_NAME))
			.putExtra(Intent.EXTRA_TEXT, body)
	}
}
