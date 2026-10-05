package dev.dshtabs

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The accept-unknown / refuse-changed rule, which nothing else in this project checks.
 *
 * `TrustOnFirstUse` is the reason `HostKeys.kt` exists at all: JSch's own
 * `StrictHostKeyChecking=no` accepts a *changed* host key silently, because its
 * changed-key branch is only reached for `ask` and `yes`. The whole security claim of
 * this client therefore rests on three answers from one pure function, and **it had no
 * test**. It does not need a device to have one: `check` takes a host name and the raw
 * key blob, and this task runs on the JVM with JSch on the test classpath.
 *
 * The refusal case is the one worth being careful about, because a test for a refusal
 * can pass for the wrong reason — an exception from anywhere at all looks the same from
 * the outside. So the assertion is on **what the exception says**: it has to name the
 * key type that arrived, read out of the blob's wire format. A `Changed` thrown before
 * the key was looked at, or by something else in the call, cannot produce that string.
 */
class TrustOnFirstUseTest {

	/**
	 * A host key blob in the SSH wire format: a four-byte big-endian length, then the
	 * algorithm name, then the key material.
	 *
	 * Built here rather than read off a device, because what `check` compares is the
	 * base64 of these bytes and nothing else — there is no parsing of the key material
	 * to get wrong. A real Ed25519 blob is 51 bytes; the shape of that is what this
	 * reproduces, down to the length prefix, which is the part `Changed` reads to name
	 * the algorithm.
	 */
	private fun blob(type: String, material: ByteArray = ByteArray(32) { it.toByte() }): ByteArray {
		val out = ByteArrayOutputStream()
		val name = type.toByteArray(Charsets.US_ASCII)
		for (shift in intArrayOf(24, 16, 8, 0)) out.write((name.size shr shift) and 0xFF)
		out.write(name)
		out.write(material)
		return out.toByteArray()
	}

	private fun base64(key: ByteArray): String = Base64.getEncoder().encodeToString(key)

	@Test
	fun `a host with nothing pinned is accepted, and the key is reported so it can be pinned`() {
		var learned: String? = null
		val repository = TrustOnFirstUse(pinned = null) { learned = it }
		val presented = blob("ssh-ed25519")

		assertEquals(
			"a key never seen before must be accepted — that is the first half of trust on " +
				"first use, and refusing it would make the app unable to reach any machine",
			HostKeyRepository.OK,
			repository.check("box-a", presented)
		)
		assertEquals(
			"the key was accepted but not reported, so nothing can pin it and the *next* " +
				"connect has nothing to compare against — the app would trust a changed key " +
				"for ever",
			base64(presented),
			learned
		)
	}

	@Test
	fun `a host whose pinned key matches is accepted, and nothing is reported again`() {
		val pinned = blob("ssh-ed25519")
		var learned: String? = null
		val repository = TrustOnFirstUse(pinned = base64(pinned)) { learned = it }

		assertEquals(
			"the key that was pinned has to be accepted, or every later connect to that " +
				"machine fails",
			HostKeyRepository.OK,
			repository.check("box-a", pinned)
		)
		assertEquals(
			"a matching key is not a first use, and reporting it as one would overwrite the " +
				"pin on every connect",
			null,
			learned
		)
	}

	/**
	 * The refusal, or a failure if the changed key was accepted.
	 *
	 * Deliberately a try/catch and an assert rather than a message attached to the
	 * `check` call: an exception that is *not* `Changed` has to reach the runner as
	 * itself, so that "it refused" and "it refused for the wrong reason" cannot be
	 * confused for each other.
	 */
	private fun refusalOf(repository: TrustOnFirstUse, host: String, key: ByteArray): TrustOnFirstUse.Changed {
		var thrown: TrustOnFirstUse.Changed? = null
		try {
			repository.check(host, key)
		} catch (error: TrustOnFirstUse.Changed) {
			thrown = error
		}
		assertTrue(
			"a host key that differs from the pinned one was accepted. This is exactly what " +
				"JSch's StrictHostKeyChecking=no does and the one thing this class exists to " +
				"prevent: a rebuilt machine, or something in the middle, is trusted without a " +
				"word.",
			thrown != null
		)
		return thrown!!
	}

	@Test
	fun `a changed key is refused, and the refusal names the key that arrived`() {
		val pinned = blob("ssh-ed25519")
		val changed = blob("ssh-rsa")
		var learned: String? = null
		val repository = TrustOnFirstUse(pinned = base64(pinned)) { learned = it }

		val thrown = refusalOf(repository, "box-a", changed)

		// The non-vacuity guard: `Changed` is constructed with the algorithm read out of
		// the blob that arrived, so a message naming `ssh-rsa` can only come from a check
		// that actually compared the presented key against the pin.
		assertTrue(
			"the refusal does not name the key type that arrived (message: " +
				"${thrown.message}). A `Changed` thrown before the key was read would look " +
				"identical from a caller's side, so the message is the only thing that " +
				"distinguishes a real comparison from a refusal that happens by accident.",
			thrown.message?.contains("ssh-rsa") == true
		)
		assertTrue(
			"the refusal does not name the machine, so the operator cannot tell which of " +
				"their tabs is being refused (message: ${thrown.message})",
			thrown.message?.contains("box-a") == true
		)
		assertEquals(
			"the refused key was also reported as a first use, which would pin the very key " +
				"that was just refused",
			null,
			learned
		)
	}

	@Test
	fun `a key that is not there at all is not included`() {
		var learned: String? = null
		val repository = TrustOnFirstUse(pinned = null) { learned = it }
		assertEquals(
			"a null key means the server offered none, which is not the same as a key that " +
				"was accepted: answering OK here would pin the absence of a key",
			HostKeyRepository.NOT_INCLUDED,
			repository.check("box-a", null)
		)
		assertFalse("nothing was presented, so nothing may be pinned", learned != null)
	}
}
