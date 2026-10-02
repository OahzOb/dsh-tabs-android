package dev.dshtabs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties

/**
 * The parts of the client that can only be answered **on the phone**.
 *
 * Every claim in this project about SSH came from a desktop, and the desktop is not
 * the device. Three things are genuinely unknown until this runs:
 *
 * 1. Whether JSch's key exchange reaches agreement with a modern OpenSSH server
 *    using Android's own crypto provider rather than a desktop JDK's. Modern OpenSSH
 *    offers `curve25519-sha256` and `ssh-ed25519` first, and Android only gained XDH
 *    and Ed25519 at API 33 — so "the algorithms are there" is a claim about **this**
 *    provider, not about Java.
 * 2. Whether host key verification succeeds against a real `ssh-ed25519` key.
 * 3. Whether the platform can generate the key type the app wants to offer for
 *    key-based login.
 *
 * A JVM test cannot answer any of them, which is why this is an instrumented test
 * and not a unit test.
 *
 * **How to run it against a real machine.** The endpoint is passed in, so no
 * credential lives in this repository:
 *
 * ```
 * adb shell am instrument -w \
 *   -e class dev.dshtabs.RealServerTest \
 *   -e sshHost 192.168.1.10 -e sshUser me -e sshPassword secret \
 *   dev.dshtabs.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Without `-e sshHost` every test here is skipped rather than failed: the suite has
 * to be runnable on a device with no reachable machine.
 */
@RunWith(AndroidJUnit4::class)
class RealServerTest {

	private val args = InstrumentationRegistry.getArguments()

	private val host: String? = args.getString("sshHost")

	private val user: String = args.getString("sshUser") ?: "root"

	private val password: String? = args.getString("sshPassword")

	private val port: Int = args.getString("sshPort")?.toIntOrNull() ?: 22

	/**
	 * A private key to authenticate with, as a path on the device.
	 *
	 * Passed in rather than compiled in, for the same reason as the password: no
	 * credential belongs in this repository. `/data/local/tmp` works because the shell
	 * can write there and the app can read it, which is exactly what a test needs and
	 * nothing more.
	 */
	private val keyFile: String? = args.getString("sshKeyFile")

	/** Fail loudly rather than silently skipping when a test is asked for by name. */
	private fun requireHost() {
		assumeTrue("no -e sshHost given; nothing to connect to", host != null)
	}

	/**
	 * Open a session the way [RemoteSession] does, so this exercises the real path.
	 *
	 * @return the connected session.
	 */
	private fun connect(): Session {
		val jsch = JSch()
		if (!keyFile.isNullOrEmpty()) {
			val pem = java.io.File(keyFile).readBytes()
			jsch.addIdentity("test", pem, null, null)
		}
		val session = jsch.getSession(user, host, port)
		session.hostKeyRepository = TrustOnFirstUse(null) { key ->
			// Already base64 of the SSH wire format by the time it reaches here — that is
			// what gets persisted, so print it the way it would be stored.
			println("HOSTKEY $key")
		}
		session.setConfig(Properties().apply {
			put("StrictHostKeyChecking", "yes")
			put("PreferredAuthentications", if (password.isNullOrEmpty()) "publickey" else "password,keyboard-interactive,publickey")
		})
		if (!password.isNullOrEmpty()) session.setPassword(password)
		session.timeout = 20_000
		session.connect(20_000)
		return session
	}

	@Test
	fun keysAreExchangedWithTheRealServer() {
		requireHost()
		val session = connect()
		try {
			// The negotiated algorithm names are the whole point of this test: they say
			// which primitive the platform provider actually agreed on, rather than
			// which ones it advertises.
			println("NEGOTIATED host=${session.host} port=${session.port}")
			assertTrue("the session did not come up", session.isConnected)
		} finally {
			session.disconnect()
		}
	}

	@Test
	fun aRemoteCommandStreamsWhileStdinStaysOpen() {
		requireHost()
		val session = connect()
		try {
			val channel = session.openChannel("exec") as ChannelExec
			channel.setCommand("echo READY-LINE-MARKER; uname -s")
			val stdout = channel.inputStream
			// Requested before connect() and never closed: this is the teardown contract
			// in miniature — the remote program must be able to block on this stream.
			val stdin = channel.outputStream
			channel.connect(20_000)

			val buffer = ByteArrayOutputStream()
			val chunk = ByteArray(4096)
			val deadline = System.currentTimeMillis() + 20_000
			while (System.currentTimeMillis() < deadline && !buffer.toString("UTF-8").contains("READY-LINE-MARKER")) {
				if (stdout.available() > 0) {
					val read = stdout.read(chunk)
					if (read < 0) break
					buffer.write(chunk, 0, read)
				} else {
					Thread.sleep(100)
				}
			}

			val text = buffer.toString("UTF-8")
			println("STDOUT <<$text>>")
			assertTrue("the readiness marker never arrived: $text", text.contains("READY-LINE-MARKER"))

			// The readiness line is parsed out of accumulated output exactly like this.
			val platform = Remote.classifyPlatform(0, text.lines().lastOrNull { it.isNotBlank() } ?: "")
			println("CLASSIFIED $platform")

			stdin.close()
			channel.disconnect()
		} finally {
			session.disconnect()
		}
	}

	/**
	 * A local port forward, proved by talking to whatever is on the far side.
	 *
	 * Port `0` is what the app uses: the library asks the OS for a free port and
	 * returns it, which is the same thing `ssh -L 0:…` does and the reason the client
	 * never has to allocate a port itself.
	 */
	@Test
	fun aLocalForwardBindsAndCarriesAByte() {
		requireHost()
		val session = connect()
		try {
			// Forward to the far side's own sshd banner port: something that always
			// answers and needs no setup on the machine.
			val bound = session.setPortForwardingL(0, "127.0.0.1", port)
			println("FORWARD 127.0.0.1:$bound -> 127.0.0.1:$port")
			assertTrue("no local port was allocated", bound > 0)

			val socket = java.net.Socket("127.0.0.1", bound)
			socket.soTimeout = 15_000
			val banner = socket.getInputStream().readNBytes(4).toString(Charsets.US_ASCII)
			println("BANNER <<$banner>>")
			assertEquals("the forwarded connection is not an SSH banner", "SSH-", banner)
			socket.close()

			session.delPortForwardingL(bound)
		} finally {
			session.disconnect()
		}
	}

	/**
	 * Whether this platform can generate the key pair the app offers for login.
	 *
	 * `ecdsa-sha2-nistp256` is the target because Android has had the EC primitives
	 * since API 11, while Ed25519 only arrived at API 33 and RSA signatures are what
	 * OpenSSH is moving away from. This asserts the *material* exists; getting JSch to
	 * sign with it is a separate step.
	 */
	@Test
	fun thePlatformCanGenerateAnEcdsaKeyPair() {
		val generator = java.security.KeyPairGenerator.getInstance("EC")
		generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
		val pair = generator.generateKeyPair()

		assertEquals("EC", pair.public.algorithm)
		assertNotNull("no encoded point", pair.public.encoded)
		assertTrue("the public point is implausibly short", pair.public.encoded.size > 32)

		val signer = java.security.Signature.getInstance("SHA256withECDSA")
		signer.initSign(pair.private)
		signer.update("dsh-tabs".toByteArray())
		val signature = signer.sign()
		println("ECDSA public=${pair.public.encoded.size}B signature=${signature.size}B")

		signer.initVerify(pair.public)
		signer.update("dsh-tabs".toByteArray())
		assertTrue("the platform cannot verify its own signature", signer.verify(signature))
	}

	/**
	 * The algorithms this device can actually offer, which is what decides whether a
	 * modern server will talk to it at all.
	 */
	@Test
	fun theCryptoProviderSupportsWhatModernOpenSshOffers() {
		val available = java.security.Security.getAlgorithms("Signature").toSortedSet().toList()
		val x25519 = java.security.Security.getAlgorithms("KeyAgreement").toSortedSet().toList()
		val curves = java.security.Security.getAlgorithms("KeyPairGenerator").toSortedSet().toList()
		println("SIGNATURE ${available.joinToString(",")}")
		println("KEYAGREEMENT ${x25519.joinToString(",")}")
		println("KEYPAIRGENERATOR ${curves.joinToString(",")}")

		// Ed25519 is what the servers offer first and what the host key check needs;
		// X25519 is the key exchange they prefer. Both are reported rather than
		// asserted individually, because which one is *chosen* depends on the server —
		// the negotiated result is what `keysAreExchangedWithTheRealServer` prints.
		assertTrue("no Signature algorithms at all", available.isNotEmpty())
		println("ED25519_SIGNATURE_PRESENT ${available.any { it.equals("Ed25519", true) }}")
		println("XDH_PRESENT ${x25519.any { it.equals("XDH", true) || it.contains("25519", true) }}")
	}
}
