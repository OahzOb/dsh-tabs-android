package dev.dshtabs

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One remote machine's Harness, reached over one SSH connection.
 *
 * The desktop app needs **two** `ssh` processes per device — one to run the server,
 * one bare `ssh -N -L` for the forward — but only because `ssh` is a program with
 * one job per invocation. An SSH library has no such limitation, so this class asks
 * a single [Session] for both an exec channel and a local forward: the same protocol
 * work over one connection, with one thing to tear down instead of two.
 *
 * The **teardown contract** survives the move intact and is why this class is so
 * careful about stdin. The remote program blocks on stdin precisely so that the
 * connection ending always reaps the server, however it ends — measured on the
 * desktop side, where a plain remote `dsh web` outlived its client being killed
 * outright. Closing stdin is therefore the first step of [stop], and never anything
 * else.
 *
 * **The forward is JSch's own**, installed with [Session.setPortForwardingL] rather
 * than a `ServerSocket` this class accepts on. That matters more than it looks: the
 * hand-rolled version would have to implement HTTP/1.1 correctly — persistent
 * connections, chunked framing, `Upgrade` for WebSocket, half-close — and every one
 * of those is a documented requirement that a naive byte-pump gets wrong. JSch
 * already bridges a loopback connection to a `direct-tcpip` channel, which is the
 * same thing `ssh -L` does and is exercised by every user of the library.
 */
class RemoteSession private constructor(
	private val session: Session,
	private val channel: ChannelExec,
	/** The remote program's stdin. Held open for the whole life of the session. */
	private val stdin: OutputStream,
	/** The port JSch bound on loopback, chosen by the OS. */
	val localPort: Int,
	val remotePort: Int,
	val token: String?,
	val platform: Remote.Platform,
	/** Everything the connect said, for the failure panel. */
	val transcript: List<String>
) {
	/** The URL a WebView should open. */
	val url: String get() = Remote.localUrl(localPort, token)

	/**
	 * Stop the remote server, then drop the connection.
	 *
	 * The order is load-bearing and is the same one the desktop app uses: ending
	 * stdin reaches EOF on the far side, which is what makes the remote `dsh web`
	 * exit. Disconnecting first would drop the connection without the far side ever
	 * learning it should stop, leaving an orphaned server holding the remote port.
	 */
	fun stop() {
		watchJob?.cancel()
		watchJob = null
		try {
			stdin.close()
		} catch (_: Exception) {
			/* the pipe may already be gone */
		}
		try {
			@Suppress("DEPRECATION")
			session.delPortForwardingL(localPort)
		} catch (_: Exception) {
			/* already gone */
		}
		try {
			channel.disconnect()
		} catch (_: Exception) {
			/* already gone */
		}
		try {
			session.disconnect()
		} catch (_: Exception) {
			/* already gone */
		}
	}

	/** True once the session is no longer usable. */
	val isAlive: Boolean get() = session.isConnected && channel.isConnected

	/** What the remote program reported when it exited, or -1 while it is still running. */
	val exitStatus: Int get() = if (channel.isClosed) channel.exitStatus else -1

	/**
	 * Watch the remote program, and report when it ends.
	 *
	 * **This is not a nicety.** The whole client rests on one assumption: while the SSH
	 * session is up, so is the Harness behind it. The remote program can end without the
	 * SSH session ending — measured, on this project's own machine, where the far side's
	 * `dsh-turn-restart` plugin rewrote its profile mid-turn: the server exited, the
	 * session stayed perfectly healthy, and nothing in the connection said so. The tab
	 * went on claiming to be running, the forward pointed at a dead port, and the
	 * interface sat in `connection lost, retry #50` while the operator watched a task
	 * that was never going to finish.
	 *
	 * The exec channel is the signal that exists: it closes when the remote program
	 * does. Polling is the honest mechanism, because JSch offers no callback for this —
	 * the alternative is to wait for output that will never arrive.
	 *
	 * @param intervalMs how often to look; a few seconds is enough for a human.
	 * @param onEnded called with the exit status, on the main dispatcher.
	 */
	fun watchUntilEnded(intervalMs: Long = 4_000, onEnded: (Int) -> Unit) {
		watchJob = CoroutineScope(Dispatchers.IO).launch {
			while (isActive) {
				delay(intervalMs)
				if (!isAlive) {
					val code = exitStatus
					withContext(Dispatchers.Main) { onEnded(code) }
					return@launch
				}
			}
		}
	}

	private var watchJob: Job? = null

	companion object {
		/** How long a whole connect may take before it is declared failed. */
		private const val CONNECT_TIMEOUT_MS = 20_000

		/**
		 * Connect, start the far side's Harness, and forward a loopback port to it.
		 *
		 * @param device the machine to reach.
		 * @param credential what to authenticate with.
		 * @param onLine receives each transcript line as it happens.
		 * @param onHostKey called once, with the key to pin, when the machine's host key
		 *   was not known before. Called only after the connect has fully succeeded, so
		 *   a machine that turned out to be unreachable never gets pinned.
		 * @param commandOverride a diagnostic escape hatch: run this instead of the
		 *   program the platform would pick. The app never passes it — a client that
		 *   can be told to run arbitrary commands is a liability — but the probe needs
		 *   it to reproduce a machine whose default profile will not boot, which is a
		 *   real situation and one that otherwise cannot be tested at all.
		 */
		suspend fun open(
			device: Device,
			credential: Credential,
			onLine: (String) -> Unit,
			onHostKey: (String) -> Unit = {},
			commandOverride: String? = null
		): SessionStart =
			withContext(Dispatchers.IO) {
				val lines = mutableListOf<String>()
				val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
				val note: (String) -> Unit = { text ->
					val line = "[${stamp.format(Date())}] $text"
					synchronized(lines) { lines += line }
					onLine(line)
				}

				note("connect ${device.user}@${device.host}:${device.sshPort}")

				var session: Session? = null
				var channel: ChannelExec? = null
				try {
					// The pinned host key has to be readable from JSch's transport thread,
					// so it is latched here rather than written to the device directly.
					val learned = java.util.concurrent.atomic.AtomicReference<String?>(null)

					val jsch = JSch()
					if (credential is Credential.PrivateKey) {
						// The four-argument overload, which is the only one that takes a
						// passphrase at all. `pubkey` stays null: the key carries its own.
						jsch.addIdentity(
							"dsh-tabs",
							credential.pem,
							null,
							credential.passphrase?.toByteArray(Charsets.UTF_8)
						)
					}

					session = jsch.getSession(device.user, device.host, device.sshPort).apply {
						// Host key verification is ours, so JSch must not also do its own:
						// `ask`/`yes` would consult a store this app does not keep, and `no`
						// accepts a *changed* key silently. See TrustOnFirstUse.
						hostKeyRepository = TrustOnFirstUse(device.hostKey, learned::set)
						setConfig(Properties().apply {
							put("StrictHostKeyChecking", "yes")
							put(
								"PreferredAuthentications",
								if (credential is Credential.Password) "password,keyboard-interactive,publickey" else "publickey"
							)
							// Keep the connection alive across idle periods.
							//
							// The Harness UI holds a long-lived channel open and otherwise sits
							// silent, and a NAT — a phone's carrier, or the path Tailscale takes —
							// drops a connection that stops producing packets. Measured: a session
							// that had authenticated and loaded the interface reported
							// `connection lost, retry #1…#7` about twenty seconds in, while the
							// server logged no disconnect at all, which is what a silently
							// dropped idle connection looks like from both ends.
							//
							// JSch sends the keepalive only if this property is set: the interval
							// alone is not enough, and without the property the setting is
							// silently ignored.
							put("ServerAliveInterval", "20")
							put("ServerAliveCountMax", "3")
							System.setProperty("jsch.keepalive.message", "true")
						})
						if (credential is Credential.Password) setPassword(credential.secret)
						timeout = CONNECT_TIMEOUT_MS
						connect(CONNECT_TIMEOUT_MS)
					}

					// `uname -s` is one argv element with no metacharacters, so it survives
					// every shell: a POSIX host answers on stdout while cmd.exe and
					// PowerShell fail the command and print nothing. There is no exit code
					// to inspect any more — an exception is the connection failure, which is
					// one fewer way to be wrong than the desktop branch's code 255.
					val platform = device.platform ?: runProbe(session).also {
						note("remote platform: ${if (it == Remote.Platform.WINDOWS) "windows" else "posix"}")
					}

					// The directory is the one field of a device record that ends up
					// inside a command string, and the desktop client refuses a small
					// set of characters it cannot place there safely. This client has
					// no shell in the path and could accept them — it refuses the same
					// set so that one device book works on both, which is the point of
					// the shared format. See Remote.directoryProblem.
					Remote.directoryProblem(device.directory)?.let { problem ->
						return@withContext fail(problem, session, channel, lines)
					}

					channel = session.openChannel("exec") as ChannelExec
					channel.setCommand(
						commandOverride ?: Remote.remoteCommand(Remote.remoteProgram(device.directory, platform), platform)
					)
					// stdout has to be asked for before connect(), or JSch hands back a
					// stream that never carries the readiness line.
					val stdout = channel.inputStream
					val stderr = channel.getErrStream()
					val sink = channel.outputStream
					channel.connect(CONNECT_TIMEOUT_MS)
					note("remote program: ${if (platform == Remote.Platform.WINDOWS) "powershell" else "bash"}")

					val ready = awaitReady(stdout, stderr, note)
					if (ready.error != null) return@withContext fail(ready.error, session, channel, lines)
					val url = ready.url ?: return@withContext fail("the readiness line had no URL", session, channel, lines)
					val parsed = Remote.parseReadyUrl(url)
					if (parsed.port <= 0) return@withContext fail("the readiness line had no port: $url", session, channel, lines)
					note("dsh web: http://127.0.0.1:${parsed.port}/")

					// Port 0 asks the OS for a free one and returns it, which is exactly what
					// `ssh -L` does — the desktop app has to allocate its own port and hope
					// nothing takes it in the gap.
					val bound = session.setPortForwardingL(0, "127.0.0.1", parsed.port)
					note("tunnel 127.0.0.1:$bound -> 127.0.0.1:${parsed.port}")

					// Latch the key before tearing the repository down, and hand it over
					// only now that the whole connect has worked.
					learned.get()?.let(onHostKey)

					SessionStart.Ok(
						RemoteSession(
							session = session,
							channel = channel,
							stdin = sink,
							localPort = bound,
							remotePort = parsed.port,
							token = parsed.token,
							platform = platform,
							transcript = synchronized(lines) { lines.toList() }
						)
					)
				} catch (error: Exception) {
					return@withContext fail(error.message ?: error.javaClass.simpleName, session, channel, lines)
				}
			}

		private fun fail(reason: String, session: Session?, channel: ChannelExec?, lines: MutableList<String>): SessionStart {
			synchronized(lines) { lines += "[--:--:--] FAILED: $reason" }
			try {
				channel?.disconnect()
			} catch (_: Exception) {
			}
			try {
				session?.disconnect()
			} catch (_: Exception) {
			}
			return SessionStart.Failed(reason, synchronized(lines) { lines.toList() })
		}

		private fun runProbe(session: Session): Remote.Platform {
			val probe = session.openChannel("exec") as ChannelExec
			probe.setCommand("uname -s")
			val out = probe.inputStream
			probe.connect(CONNECT_TIMEOUT_MS)
			val text = out.readBytes().toString(Charsets.UTF_8)
			probe.disconnect()
			return Remote.classifyPlatform(0, text).platform ?: Remote.Platform.POSIX
		}

		/**
		 * Wait for the readiness line, watching both streams.
		 *
		 * Both are watched for the reason the desktop app learned: `dsh web` prints the
		 * URL on stdout, but anything it complains about on the way lands on stderr, so
		 * waiting on stdout alone reports a timeout with an empty transcript.
		 *
		 * Only **complete lines** are matched. The pattern ends in `\S+`, which happily
		 * matches a URL a pipe split mid-write, so matching the raw buffer resolves with
		 * a truncated URL — a connect that succeeds or fails on how the operating system
		 * chunked the output, which is the worst kind of intermittent.
		 */
		private suspend fun awaitReady(
			stdout: InputStream,
			stderr: InputStream,
			note: (String) -> Unit
		): Ready = withContext(Dispatchers.IO) {
			val done = AtomicBoolean(false)
			val result = CompletableFuture<Ready>()
			val raw = StringBuilder()
			// Kept apart from `raw`, which exists to be searched for the readiness line.
			// This is what a failure quotes back to the operator: measured against a
			// real Windows host, the far side said `Set-Location : Cannot find path …`
			// and went on saying nothing, because a failed start left the client waiting
			// for a readiness line rather than reporting what it had already been told.
			val errors = StringBuilder()

			fun finish(ready: Ready) {
				if (done.compareAndSet(false, true)) result.complete(ready)
			}

			fun watch(stream: InputStream, prefix: String) {
				thread(isDaemon = true, name = "dsh-ready${prefix.trim()}") {
					val buffer = ByteArray(4096)
					try {
						while (!done.get()) {
							val read = stream.read(buffer)
							if (read < 0) break
							val chunk = String(buffer, 0, read, Charsets.UTF_8)
							chunk.split("\n").forEach { line ->
								if (line.isNotBlank()) note(prefix + line.trimEnd('\r'))
							}
							synchronized(raw) {
								raw.append(chunk)
								if (prefix.isNotEmpty()) errors.append(chunk)
								Remote.readyUrl(raw.toString())?.let { finish(Ready(url = it)) }
							}
						}
						// Printing has stopped, so a URL without its trailing newline is as
						// complete as it will ever be — and if there is none, what the far
						// side said is the whole answer.
						synchronized(raw) {
							Remote.readyUrl(raw.toString(), partialToo = true)?.let { finish(Ready(url = it)) }
							val said = Remote.readableStderr(errors.toString())
							finish(
								Ready(
									error = if (said.isEmpty()) {
										"the remote command exited before announcing a URL"
									} else {
										"the remote command exited before announcing a URL — it said: $said"
									}
								)
							)
						}
					} catch (_: Exception) {
						/* the channel closed; the wait below reports it */
					}
				}
			}

			watch(stdout, "")
			watch(stderr, "! ")

			try {
				result.get(Remote.START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
			} catch (_: Exception) {
				finish(Ready(error = "the Harness never announced a URL within ${Remote.START_TIMEOUT_MS / 1000}s"))
				result.getNow(Ready(error = "the wait ended"))
			}
		}

		private data class Ready(val url: String? = null, val error: String? = null)
	}
}

/**
 * The outcome of starting one remote Harness.
 *
 * Top-level rather than nested: nested inside the companion object, references to it
 * from other files did not resolve, and a result type that needs a qualified path
 * through a companion is a result type nobody will name correctly.
 */
sealed interface SessionStart {
	data class Ok(val session: RemoteSession) : SessionStart
	data class Failed(val error: String, val transcript: List<String>) : SessionStart
}

/**
 * What to authenticate with.
 *
 * Two cases rather than a nullable string, because they are not interchangeable and
 * the differences are load-bearing: a password goes to `setPassword`, and a key goes
 * to `addIdentity` as **bytes**.
 *
 * The key is bytes rather than a path on purpose, and the reason is a bug this
 * project already paid for. JSch's `addIdentity` overloads are:
 *
 * ```
 * addIdentity(String name)
 * addIdentity(String name, byte[] prvkey, byte[] pubkey, byte[] passphrase)
 * addIdentity(String name, String prvfile, String pubfile)
 * ```
 *
 * There is no `(name, prvfile, passphrase)` — so passing a path and a passphrase
 * silently lands on `(name, prvfile, pubfile)` and makes JSch try to open a file
 * named after the passphrase. Reading the file here instead means the bytes and the
 * passphrase each go to the argument that actually wants them.
 */
sealed interface Credential {
	/** A password, typed by the operator and never persisted. */
	data class Password(val secret: String) : Credential

	/**
	 * A private key, in the OpenSSH format, with the passphrase that decrypts it.
	 *
	 * @param pem the key's bytes, as written to disk; may be encrypted.
	 * @param passphrase the passphrase, or null for an unencrypted key.
	 */
	class PrivateKey(val pem: ByteArray, val passphrase: String?) : Credential {
		// Holds a ByteArray, so these are written by hand or two credentials holding the
		// same key compare as unequal and the message reads like a caller's bug.
		override fun equals(other: Any?): Boolean =
			this === other || (other is PrivateKey && pem.contentEquals(other.pem) && passphrase == other.passphrase)

		override fun hashCode(): Int = 31 * pem.contentHashCode() + (passphrase?.hashCode() ?: 0)
	}
}
