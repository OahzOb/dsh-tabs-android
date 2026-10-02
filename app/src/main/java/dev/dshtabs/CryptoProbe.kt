package dev.dshtabs

import android.content.Intent
import android.util.Log
import java.io.File

/**
 * The one question a desktop cannot answer about this client.
 *
 * Every claim this project makes about SSH came from a Windows machine, and the
 * device is the variable that matters: modern OpenSSH offers `ssh-ed25519` host keys
 * and `curve25519-sha256` key exchange first, while Android only gained Ed25519 and
 * XDH at API 33. "The algorithms are there" is a claim about **this** provider, and
 * whether a real OpenSSH server will complete a handshake with it is not knowable by
 * reading code or by running the same library on a JDK.
 *
 * It drives the **real** [RemoteSession] rather than a copy, which makes it a
 * stronger test than a mock would be: it is the same code path a tab takes, with the
 * device subject substituted.
 *
 * **How it is triggered.** It cannot be its own exported activity — an exported
 * diagnostic that dials an arbitrary host and authenticates with an arbitrary key is
 * a liability, and this phone's MIUI refuses to install a second test package
 * without an on-screen approval anyway. So it hangs off the launcher activity, which
 * is the only component adb can start:
 *
 * ```
 * adb shell am start -n dev.dshtabs/.MainActivity \
 *   --es probeHost 127.0.0.1 --ei probePort 2222 --es probeUser me \
 *   --es probeKeyFile /data/local/tmp/id_test
 * ```
 *
 * With no `probeHost` extra nothing here runs. Results go to logcat under
 * `DshCryptoProbe`, because the point is to read them from a machine.
 *
 * **That arrangement is only safe because of [requested]'s build check, and the
 * check is not decoration.** The launcher activity has to be exported, so *any*
 * installed app — not just adb, and without root — can start it with extras of its
 * own choosing. Everything below then becomes reachable to that app:
 *
 * - `probeUseAppKey` makes the app authenticate with **this app's real private
 *   key**, and `probeHost` decides which machine receives it. The key is offered to
 *   whichever host the caller names, and `onHostKey` prints the host key it is
 *   offered in return.
 * - `probeCommand` replaces the remote program with an **arbitrary command** run on
 *   that machine, under an authentication the far side believes is this app.
 * - `probeKeyFile` reads a file the caller names and uses it as a private key.
 *
 * A probe is a developer tool for a device its owner is holding, so it is confined
 * to debug builds. `release { minifyEnabled false }` means the code is still present
 * in a release APK; what stops it is this one gate, which is why it is written as a
 * conjunction with the extras rather than left to `MainActivity` to remember.
 */
object CryptoProbe {

	private const val TAG = "DshCryptoProbe"

	/**
	 * True when this launch was a probe rather than a normal start.
	 *
	 * Debug builds only, deliberately — see the class documentation for what becomes
	 * reachable to any other app on the device without this. `BuildConfig.DEBUG` is
	 * false in a release build, so the extras are inert there however they arrived.
	 */
	fun requested(intent: Intent?): Boolean =
		BuildConfig.DEBUG &&
			(intent?.getStringExtra("probeHost") != null || intent?.getBooleanExtra("probeKeygen", false) == true)

	/**
	 * Generate the app's own key and report the half that goes on a machine.
	 *
	 * The only way to test this without a screen: the key is made by the same code the
	 * button calls, written where the app writes it, and printed so it can be pasted
	 * into a real `authorized_keys` — after which a connect proves the round trip
	 * rather than just proving that a string was produced.
	 */
	fun keygen() {
		say("KEYGEN existing=${Keys.exists()}")
		say("KEYGEN ${Keys.describe()}")
		try {
			val public = Keys.generate()
			say("KEYGEN public=$public")
			// Print the exact arguments the read will use, and whether the file is there
			// at that instant. A "file not found" naming the *passphrase* means the two
			// arguments are crossed somewhere, and guessing which is slower than looking.
			say("KEYGEN ${Keys.describe()}")
			val (again, failure) = Keys.publicKeyOrError()
			say("KEYGEN reread=${again != null} failure=${failure ?: "none"}")
			val (third, thirdFailure) = Keys.publicKeyOrError()
			say("KEYGEN rereadAgain=${third != null} failure=${thirdFailure ?: "none"}")
		} catch (error: Throwable) {
			say("KEYGEN FAILED ${error.javaClass.name}: ${error.message}")
			val (_, failure) = Keys.publicKeyOrError()
			say("KEYGEN readFailure=${failure ?: "none"}")
		}
	}

	fun say(message: String) {
		Log.i(TAG, message)
	}

	/**
	 * Start a session from the extras on an intent — the connect half of the probe.
	 *
	 * Shared with the WebView probe so both drive exactly the same code, and so a
	 * change to how the probe authenticates cannot make one of them meaningless.
	 */
	suspend fun openSession(intent: Intent): SessionStart {
		val host = intent.getStringExtra("probeHost") ?: return SessionStart.Failed("no host", emptyList())
		val port = intent.getIntExtra("probePort", Remote.DEFAULT_SSH_PORT)
		val user = intent.getStringExtra("probeUser") ?: "root"
		val keyFile = intent.getStringExtra("probeKeyFile")
		val password = intent.getStringExtra("probePassword")

		val device = Device(id = "probe", label = "probe", host = host, user = user, sshPort = port)
		val credential = when {
			// The app's own key, exactly as a connect would use it — so this tests the
			// stored key and its passphrase rather than a copy handed in by the caller.
			intent.getBooleanExtra("probeUseAppKey", false) -> Keys.credentialFor(null)
			keyFile != null -> Credential.PrivateKey(File(keyFile).readBytes(), null)
			else -> Credential.Password(password.orEmpty())
		}
		say("PROBE credential=${credential.javaClass.simpleName} keyExists=${Keys.exists()}")

		// **Temporary.** The junction failure survived the fix that was supposed to
		// remove it, so the next useful thing is not another hypothesis but the
		// resolution itself. This asks the remote what it chose, reusing the same
		// `windowsResolve()` the real program uses rather than a copy of it.
		val diag = intent.getBooleanExtra("probeDiag", false)
		val command = intent.getStringExtra("probeCommand")?.takeIf { it.isNotBlank() }
			?: if (diag) {
				Remote.remoteCommand(
					Remote.windowsResolve() +
						"; [Console]::Error.WriteLine(\"DIAG dsh=\$dsh\")" +
						"; [Console]::Error.WriteLine(\"DIAG shimDir=\$((Split-Path -Parent \$dsh))\")" +
						"; [Console]::Error.WriteLine(\"DIAG pathNode=\$((Get-Command node -ErrorAction SilentlyContinue).Source)\")" +
						"; [Console]::Error.WriteLine(\"DIAG cwd=\$((Get-Location).Path)\")" +
						"; \$binJs = Join-Path (Split-Path -Parent \$dsh) 'node_modules\\@deepseek-ai\\dsh\\lib\\bin.js'" +
						"; [Console]::Error.WriteLine(\"DIAG binJs=\$binJs exists=\$(Test-Path -LiteralPath \$binJs)\")" +
						"; [Console]::Error.WriteLine(\"DIAG nodeAtProgramFiles=\$(Test-Path -LiteralPath (Join-Path \$env:ProgramFiles 'nodejs\\node.exe'))\")",
					Remote.Platform.WINDOWS
				)
			} else {
				null
			}

		return RemoteSession.open(
			device = device,
			credential = credential,
			onLine = { line -> say("LINE $line") },
			onHostKey = { key -> say("HOSTKEY $key") },
			// Null means "the program the platform would pick"; anything else is a
			// diagnostic, so a probe run can never be mistaken for the app's own behaviour.
			commandOverride = command
		)
	}

	/**
	 * Handshake, forward a byte, tear down — the whole client, on this device.
	 *
	 * @param intent carries the endpoint and the credential.
	 */
	suspend fun run(intent: Intent) {
		val host = intent.getStringExtra("probeHost") ?: return
		val port = intent.getIntExtra("probePort", Remote.DEFAULT_SSH_PORT)
		val user = intent.getStringExtra("probeUser") ?: "root"
		val keyFile = intent.getStringExtra("probeKeyFile")

		say("PROBE start host=$host port=$port user=$user key=${keyFile != null}")

		// What this provider advertises. Reported rather than asserted: the negotiated
		// result below is the answer that matters, and a provider can advertise an
		// algorithm it cannot actually complete a handshake with.
		val signatures = java.security.Security.getAlgorithms("Signature").toSortedSet()
		val agreements = java.security.Security.getAlgorithms("KeyAgreement").toSortedSet()
		say("ED25519_SIGNATURE=${signatures.any { it.equals("Ed25519", true) }}")
		say("XDH_KEYAGREEMENT=${agreements.any { it.equals("XDH", true) }}")
		say("BOUNCY_CASTLE=${java.security.Security.getProvider("BC") != null}")

		when (val result = openSession(intent)) {
			is SessionStart.Failed -> say("FAILED ${result.error}")
			is SessionStart.Ok -> finish(result.session)
		}
	}

	/**
	 * The token exchange and an authenticated request, exactly as a browser does them.
	 *
	 * This is the one part of the protocol that lives in the **client** rather than in
	 * the remote program, and it is only ever exercised by a real page load: the
	 * readiness URL carries a one-time token, the server answers with a signed cookie
	 * and a redirect to `./`, and every later request on that origin rides the cookie.
	 *
	 * Nothing in the app reads a cookie — WebView's cookie jar handles it — which is
	 * precisely why it is worth proving that the jar *would* have what it needs, and
	 * that the document beyond it is really the Harness. A failure here is
	 * distinguishable from a failure in the WebView, which is the point.
	 */
	suspend fun exerciseHttp(session: RemoteSession): Boolean {
		val localPort = session.localPort
		var cookie: String? = null

		try {
			val tokenReply = request(localPort, session.url.substringAfter("http://127.0.0.1:$localPort"), null)
			say("HTTP_TOKEN status=${tokenReply.status} location=${tokenReply.location}")
			cookie = tokenReply.cookie
			if (cookie == null) {
				say("HTTP_TOKEN no Set-Cookie — the WebView would have nothing to send")
				return false
			}
		} catch (error: Throwable) {
			say("HTTP_TOKEN_FAILED ${error.javaClass.simpleName}: ${error.message}")
			return false
		}

		return try {
			val root = request(localPort, "/", cookie)
			say("HTTP_ROOT status=${root.status} location=${root.location}")
			val harness = root.body.contains("__DSH_BOOT__") || root.body.contains("DeepSeek") || root.body.contains("Harness")
			say("HTTP_BODY bytes=${root.body.length} looksLikeHarness=$harness")
			harness
		} catch (error: Throwable) {
			say("HTTP_ROOT_FAILED ${error.javaClass.simpleName}: ${error.message}")
			false
		}
	}

	/** One HTTP/1.0 request, with just enough parsing to read the status, headers and body. */
	private suspend fun request(port: Int, path: String, cookie: String?): HttpReply =
		// Off the main thread: a raw socket is a blocking network call and the platform
		// throws NetworkOnMainThreadException for one. Measured twice — first in the
		// forward check, then again here, which is why the callers are `suspend`.
		kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
			val socket = java.net.Socket("127.0.0.1", port)
			socket.soTimeout = 20_000
			val request = buildString {
				append("GET ").append(path).append(" HTTP/1.0\r\n")
				// The far side's browser-trust fence reads Host, and `dsh web` uses it to
				// decide whether the request came from its own machine. Forwarding makes
				// this `127.0.0.1:<local port>`, which is why the tunnel works at all.
				append("Host: 127.0.0.1:").append(port).append("\r\n")
				if (cookie != null) append("Cookie: ").append(cookie).append("\r\n")
				append("Connection: close\r\n\r\n")
			}
			socket.getOutputStream().write(request.toByteArray())
			socket.getOutputStream().flush()
			val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
			socket.close()

			val head = raw.substringBefore("\r\n\r\n")
			val body = raw.substringAfter("\r\n\r\n", "")
			val status = Regex("""HTTP/[\d.]+ (\d+)""").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: -1
			val location = Regex("""(?im)^location:\s*(.+)$""").find(head)?.groupValues?.get(1)?.trim()
			// The cookie is the name=value pair only; the attributes belong to the jar.
			val setCookie = Regex("""(?im)^set-cookie:\s*(.+)$""").find(head)?.groupValues?.get(1)?.trim()?.substringBefore(";")
			HttpReply(status, location, setCookie, body)
		}

	private data class HttpReply(val status: Int, val location: String?, val cookie: String?, val body: String)

	private suspend fun finish(session: RemoteSession) {
		say("OK platform=${session.platform} localPort=${session.localPort} remotePort=${session.remotePort}")
		say("URL ${session.url}")

		// The forward has to carry bytes, or the WebView would have nothing to load. A
		// 401 is a perfectly good answer here: it proves the request reached the Harness
		// and was refused for want of the token, which is the fence working.
		//
		// Off the main thread, because a raw socket is a blocking network call and
		// Android throws NetworkOnMainThreadException for one — measured, on the first
		// run of this probe. The WebView does its own networking, so this constraint
		// belongs to the probe and not to the client.
		try {
			kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
				val socket = java.net.Socket("127.0.0.1", session.localPort)
				socket.soTimeout = 15_000
				val request = "GET / HTTP/1.0\r\nHost: 127.0.0.1:${session.localPort}\r\n\r\n"
				socket.getOutputStream().write(request.toByteArray())
				socket.getOutputStream().flush()
				val head = socket.getInputStream().readNBytes(15)
				say("FORWARD_FIRST_BYTES <<${head.toString(Charsets.US_ASCII).replace("\r", "\\r").replace("\n", "\\n")}>>")
				socket.close()
			}
		} catch (error: Throwable) {
			say("FORWARD_FAILED ${error.javaClass.simpleName}: ${error.message}")
		}

		// The token exchange, which is the client's own half of the protocol.
		val authenticated = exerciseHttp(session)
		say("HTTP_RESULT authenticated=$authenticated")

		session.stop()
		say("STOPPED")
	}
}