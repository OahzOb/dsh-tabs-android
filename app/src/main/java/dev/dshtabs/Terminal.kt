package dev.dshtabs

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An interactive shell on a device, over SSH, on a real pseudo-terminal.
 *
 * **A terminal is not a Harness tab with different contents.** The Harness tab is a
 * long-lived `exec` channel running one program and a forward to it; this is a
 * `shell` channel with a pty, and both ends have to agree about a window size for
 * the life of the channel. They do share the transport — [Ssh] — because a second,
 * differently configured way of accepting a host key is exactly the kind of second
 * security model this app must not grow.
 *
 * The class is deliberately **one session per terminal**: the desktop client opens
 * one `ssh -tt` per terminal too, and reusing the Harness tab's session would tie a
 * terminal's lifetime to a connection whose watchdog tears it down on its own. The
 * far side pays one more authenticated connection, in exchange for a terminal that
 * neither dies with a Harness restart nor keeps one alive.
 *
 * Everything here blocks and runs off the main thread; the window only sees
 * callbacks. The reader and the preset probe are daemon threads, so a process that
 * is being torn down is not held open by a shell that is still reading.
 */
class TerminalSession(
	val device: Device,
	private val credential: Credential
) {

	/** What a terminal can be doing. */
	enum class State { CONNECTING, RUNNING, FAILED, ENDED }

	/** Where output goes. Replaced when a new window adopts this session. */
	@Volatile
	var onOutput: (String) -> Unit = {}

	/** What the terminal is doing, with the one sentence a failure carries. */
	@Volatile
	var onState: (State, String?) -> Unit = { _, _ -> }

	/** Which presets the far side answered for; `null` for a command not yet asked. */
	@Volatile
	var onPresets: (TerminalPresets.Availability) -> Unit = {}

	/**
	 * Whether the window that owns this terminal is showing it.
	 *
	 * Handed to the registry on a non-finishing destroy and read back by the next
	 * window, so a configuration change returns to the terminal the operator was
	 * typing in rather than to the Harness page behind it.
	 */
	@Volatile
	var windowVisible: Boolean = false

	@Volatile
	var state: State = State.CONNECTING
		private set

	private var session: Session? = null
	private var shell: ChannelShell? = null
	private var reader: Thread? = null
	private val chunker = Utf8Chunker()
	private val writeLock = Any()

	@Volatile
	private var stopped = false

	@Volatile
	private var cols = TerminalKeys.DEFAULT_COLS

	@Volatile
	private var rows = TerminalKeys.DEFAULT_ROWS

	/** The remote platform, once the preset probe has asked. */
	private var platform: Remote.Platform? = null

	/** True while the shell channel is up and the operator has not closed it. */
	val isAlive: Boolean
		get() = !stopped && shell?.isConnected == true && session?.isConnected == true

	/**
	 * Open the session and the shell, then start reading.
	 *
	 * Suspending only for the address choice, which probes a candidate before dialling;
	 * everything after it is blocking JSch work, so the caller runs this on
	 * `Dispatchers.IO`. A failure is reported through [onState] and leaves nothing
	 * behind: anything opened is disconnected before this returns.
	 *
	 * @param learned a host key seen for the first time, for the caller to pin.
	 */
	suspend fun open(learned: (String) -> Unit = {}) {
		if (stopped) return
		try {
			val resolved = Addresses.resolve(device)
			val effective = device.copy(host = resolved.host)
			val connected = Ssh.open(effective, credential, learned)
			session = connected
			val channel = connected.openChannel("shell") as ChannelShell
			// The pty type is what makes the far side's line discipline behave like a
			// terminal: without it `TERM` is unset, the shell wraps at 80 columns and
			// full-screen programs refuse to start.
			channel.setPtyType(PTY_TYPE)
			channel.setPtySize(cols, rows, 0, 0)
			// stdout and stdin have to be asked for before connect(), or JSch hands back
			// streams that never carry anything — the same ordering rule the Harness
			// connect documents for its exec channel.
			val stdout = channel.inputStream
			channel.outputStream
			channel.connect(Ssh.CONNECT_TIMEOUT_MS)
			shell = channel
			if (stopped) {
				stop()
				return
			}
			platform = device.platform
			setState(State.RUNNING, null)
			startReader(stdout)
			probePresets(connected)
		} catch (error: Exception) {
			stop()
			setState(State.FAILED, error.message ?: error.javaClass.simpleName)
		}
	}

	/**
	 * Send what the operator typed.
	 *
	 * Bytes are written as UTF-8; xterm's own escape sequences are passed through
	 * untouched, because the shell is the program that interprets them.
	 */
	fun write(text: String) {
		if (text.isEmpty() || stopped) return
		val stream = shell?.outputStream ?: return
		try {
			synchronized(writeLock) {
				stream.write(text.toByteArray(Charsets.UTF_8))
				stream.flush()
			}
		} catch (_: Exception) {
			// The channel closed between the check and the write; the reader reports it.
		}
	}

	/**
	 * Tell the pty how big the window is.
	 *
	 * The values are clamped rather than trusted: they come from a renderer, and a
	 * bogus one must not ask the far side's shell to lay out a million cells.
	 */
	fun resize(cols: Int, rows: Int) {
		this.cols = TerminalKeys.clampCols(cols)
		this.rows = TerminalKeys.clampRows(rows)
		if (stopped) return
		try {
			shell?.setPtySize(this.cols, this.rows, 0, 0)
		} catch (_: Exception) {
			// The channel is gone; the reader reports it.
		}
	}

	/** End the shell and the session. The remote side learns by the channel closing. */
	fun stop() {
		if (stopped) return
		stopped = true
		try {
			shell?.disconnect()
		} catch (_: Exception) {
			// already gone
		}
		try {
			session?.disconnect()
		} catch (_: Exception) {
			// already gone
		}
		shell = null
		session = null
	}

	private fun setState(next: State, detail: String?) {
		state = next
		onState(next, detail)
	}

	private fun startReader(stream: InputStream) {
		reader = thread(isDaemon = true, name = "dsh-terminal-${device.id}") {
			val buffer = ByteArray(8192)
			try {
				while (!stopped) {
					val read = stream.read(buffer)
					if (read < 0) break
					if (read == 0) continue
					val text = chunker.decode(buffer, read)
					if (text.isNotEmpty()) onOutput(text)
				}
				if (!stopped) {
					val code = shell?.exitStatus ?: -1
					stop()
					setState(State.ENDED, if (code >= 0) "exit code $code" else null)
				}
			} catch (error: Exception) {
				if (!stopped) {
					stop()
					setState(State.ENDED, error.message ?: error.javaClass.simpleName)
				}
			}
		}
	}

	// ---- presets ----------------------------------------------------------

	/**
	 * Ask the far side which agents it can start, and tell the window.
	 *
	 * Best effort by design: a probe that cannot answer leaves a preset's button
	 * enabled rather than greyed out, because a dialog that hides `pi` on a machine
	 * that has it is worse than a button whose command prints `not found`.
	 */
	private fun probePresets(connected: Session) {
		thread(isDaemon = true, name = "dsh-terminal-detect-${device.id}") {
			try {
				val family = platform ?: probePlatform(connected).also { platform = it }
				val found = mutableMapOf<String, Boolean>()
				for (preset in TerminalPresets.ALL) {
					val command = preset.command ?: continue
					val result = runCatching {
						commandFound(connected, TerminalPresets.detectionCommand(command, family))
					}.getOrNull()
					if (result != null) found[preset.id] = result
				}
				onPresets(TerminalPresets.Availability(found))
			} catch (_: Exception) {
				// No platform, no probe: every preset stays enabled.
			}
		}
	}

	/** `uname -s`, classified exactly as the Harness connect classifies it. */
	private fun probePlatform(connected: Session): Remote.Platform {
		val (exit, stdout) = runCommand(connected, "uname -s")
		return Remote.classifyPlatform(exit, stdout).platform ?: Remote.Platform.POSIX
	}

	private fun commandFound(connected: Session, command: String): Boolean {
		val (exit, _) = runCommand(connected, command)
		return exit == 0
	}

	/**
	 * Run one short command on its own exec channel and return its exit code and stdout.
	 *
	 * Every read is bounded: a host that accepts the channel and then goes quiet must
	 * not hold a probe thread for ever, which is the failure `RemoteSession.runProbe`
	 * documents in full.
	 */
	private fun runCommand(connected: Session, command: String, timeoutMs: Long = EXEC_TIMEOUT_MS): Pair<Int, String> {
		val exec = connected.openChannel("exec") as ChannelExec
		try {
			exec.setCommand(command)
			val stdout = exec.inputStream
			val stderr = exec.errStream
			val out = readAsync(stdout)
			val err = readAsync(stderr)
			exec.connect(Ssh.CONNECT_TIMEOUT_MS)
			val text = out.get(timeoutMs, TimeUnit.MILLISECONDS)
			runCatching { err.get(200, TimeUnit.MILLISECONDS) }
			val deadline = System.currentTimeMillis() + timeoutMs
			while (!exec.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(10)
			val exit = if (exec.isClosed) exec.exitStatus else -1
			return exit to text
		} finally {
			try {
				exec.disconnect()
			} catch (_: Exception) {
				// already gone
			}
		}
	}

	private fun readAsync(stream: InputStream): CompletableFuture<String> {
		val future = CompletableFuture<String>()
		thread(isDaemon = true, name = "dsh-terminal-read") {
			try {
				val buffer = ByteArray(4096)
				val text = StringBuilder()
				while (text.length < MAX_PROBE_BYTES) {
					val read = stream.read(buffer)
					if (read < 0) break
					text.append(String(buffer, 0, read, Charsets.UTF_8))
				}
				future.complete(text.toString())
			} catch (error: Exception) {
				future.completeExceptionally(error)
			}
		}
		return future
	}

	companion object {
		/** The terminal type the pty is opened as. */
		const val PTY_TYPE = "xterm-256color"

		/** How long a preset probe may take, per command. */
		const val EXEC_TIMEOUT_MS = 4_000L

		/** How much of a probe's output is kept; these commands answer in one line. */
		const val MAX_PROBE_BYTES = 16 * 1024
	}
}

/**
 * The programs a terminal offers beyond the login shell.
 *
 * A preset is a **command name**, not a path: the far side's login shell decides where
 * it lives. `pi` and `opencode` are the two agents the desktop client's local
 * terminals offer, and on a phone they make sense on the *far* machine — the phone
 * cannot run either, and the machine the operator is connected to has been set up for
 * them already.
 */
object TerminalPresets {

	/**
	 * One button in the terminal's toolbar.
	 *
	 * @param id stable name, and the key probes answer under.
	 * @param label what the button says.
	 * @param command the line to type, or null for the login shell itself.
	 */
	data class Preset(val id: String, val label: String, val command: String?)

	/** The toolbar, in order: the shell first, then the agents. */
	val ALL = listOf(
		Preset("shell", "Shell", null),
		Preset("pi", "pi", "pi"),
		Preset("opencode", "opencode", "opencode")
	)

	/**
	 * Which presets the far side answered for.
	 *
	 * A missing entry means the probe did not answer, **not** that the program is
	 * absent: hiding a button on a failed probe is how an operator concludes the
	 * feature does not exist. Only an explicit `false` disables a button.
	 */
	data class Availability(val found: Map<String, Boolean> = emptyMap()) {
		fun isEnabled(preset: Preset): Boolean = preset.command == null || found[preset.id] != false
	}

	/**
	 * The line that asks a shell family whether a program is on its PATH.
	 *
	 * Both are one argv element with no metacharacters, and both answer with the exit
	 * code — the output is whatever that shell prints and is not parsed.
	 */
	fun detectionCommand(name: String, platform: Remote.Platform): String =
		if (platform == Remote.Platform.WINDOWS) "where $name" else "command -v $name"
}

/**
 * Keys a terminal has to be able to send without a physical keyboard.
 *
 * A phone's soft keyboard has no Ctrl, no Escape and no arrows; xterm.js sends those
 * as escape sequences when it does get them, so the bar's buttons only have to write
 * the same bytes xterm would.
 */
object TerminalKeys {

	/** The columns a session starts with, until the view has measured itself. */
	const val DEFAULT_COLS = 100

	/** The rows a session starts with, for the same reason. */
	const val DEFAULT_ROWS = 30

	const val MIN_COLS = 20
	const val MAX_COLS = 400
	const val MIN_ROWS = 5
	const val MAX_ROWS = 300

	const val ESC = "\u001b"
	const val TAB = "\t"
	const val ENTER = "\r"
	const val BACKSPACE = "\u007f"
	const val UP = "\u001b[A"
	const val DOWN = "\u001b[B"
	const val RIGHT = "\u001b[C"
	const val LEFT = "\u001b[D"
	const val HOME = "\u001b[H"
	const val END = "\u001b[F"
	const val CTRL_C = "\u0003"
	const val CTRL_D = "\u0004"

	fun clampCols(cols: Int): Int = cols.coerceIn(MIN_COLS, MAX_COLS)

	fun clampRows(rows: Int): Int = rows.coerceIn(MIN_ROWS, MAX_ROWS)

	/**
	 * Apply a latched Ctrl to one key.
	 *
	 * @return the control byte, or null when the key has no control meaning and the
	 *   caller should send it unchanged.
	 */
	fun withCtrl(key: String): String? {
		if (key.length != 1) return null
		val char = key[0]
		return when {
			char in 'a'..'z' -> (char - 'a' + 1).toChar().toString()
			char in 'A'..'Z' -> (char - 'A' + 1).toChar().toString()
			char == ' ' -> "\u0000"
			char == '[' -> "\u001b"
			char == '\\' -> "\u001c"
			char == ']' -> "\u001d"
			char == '^' -> "\u001e"
			char == '_' -> "\u001f"
			else -> null
		}
	}
}

/**
 * Turns the byte stream of a pty into text without splitting a character.
 *
 * A terminal's output arrives in whatever chunks the socket and the pty decided on,
 * and a multi-byte UTF-8 character sits across a chunk boundary often enough to see:
 * a Chinese character in a build log, an emoji in a prompt. Decoding each chunk on its
 * own replaces those halves with `�` in the middle of the line, which is both wrong
 * and exactly the kind of wrong a screenshot reports.
 *
 * So the tail of an incomplete character is held back until the next chunk arrives,
 * and only complete text is handed to the terminal. Invalid bytes are still decoded
 * (the platform decoder replaces them) so a stream of them cannot stall the terminal.
 */
class Utf8Chunker {

	private var pending = ByteArray(0)

	/**
	 * Decode as much of one chunk as is complete.
	 *
	 * @param bytes the chunk.
	 * @param count how many bytes of [bytes] are valid.
	 * @return the text that is complete now; empty when the chunk ends mid-character.
	 */
	fun decode(bytes: ByteArray, count: Int = bytes.size): String {
		if (count <= 0) return ""
		val combined = if (pending.isEmpty()) bytes.copyOf(count) else pending + bytes.copyOf(count)
		var index = combined.size - 1
		while (index >= 0 && (combined[index].toInt() and 0xC0) == 0x80) index--
		val cut = when {
			index < 0 -> combined.size
			else -> {
				val lead = combined[index].toInt() and 0xFF
				val expected = when {
					lead and 0x80 == 0 -> 1
					lead and 0xE0 == 0xC0 -> 2
					lead and 0xF0 == 0xE0 -> 3
					lead and 0xF8 == 0xF0 -> 4
					else -> 1
				}
				val have = combined.size - index
				if (have < expected) index else combined.size
			}
		}
		if (cut <= 0) {
			// Nothing complete in this chunk (a very long partial sequence); keep at most
			// a few bytes so a stream of continuation bytes cannot grow without bound.
			pending = if (combined.size > MAX_PENDING) ByteArray(0) else combined
			return ""
		}
		var text = String(combined, 0, cut, Charsets.UTF_8)
		pending = if (combined.size > cut) combined.copyOfRange(cut, combined.size) else ByteArray(0)
		if (pending.size > MAX_PENDING) {
			text += String(pending, Charsets.UTF_8)
			pending = ByteArray(0)
		}
		return text
	}

	companion object {
		/** A partial sequence that is this long is not a character any more. */
		const val MAX_PENDING = 4
	}
}
