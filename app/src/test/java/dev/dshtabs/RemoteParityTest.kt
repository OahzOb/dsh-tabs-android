package dev.dshtabs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two clients must build the same strings, and this is the only thing that says so.
 *
 * `Remote.kt` is a **port** of `dsh-tabs/src/remote.js`, and the desktop README used
 * to promise that a parity check ran both over the same inputs and failed on any
 * difference. That check did not exist — the file it named was never written — so
 * nothing noticed the day these two drifted. This test is that promise made real.
 *
 * **What it compares against, and why that is the right baseline.** The golden
 * strings come from `dsh-tabs/docs/contract.json`, generated there by
 * `tools/contract.cjs` and pinned by that repository's own suite. So the baseline is
 * not "whatever the desktop code happened to print when this snapshot was taken" —
 * it is the desktop's published protocol contract, and a change to it fails over
 * there first, in a diff a reviewer can read.
 *
 * **It reads that file from the sibling repository, with no copy.** The two
 * repositories live under one parent (`dsh-workspace/`) precisely so this is
 * possible, and a copy would only create a second thing to keep in step — the
 * arrangement that let the first version of this guarantee rot. The resource
 * fallback exists for a checkout of this repository on its own, where the desktop
 * one is absent; when neither is present the test fails and says so, rather than
 * passing quietly with nothing to compare against.
 */
class RemoteParityTest {

	/**
	 * The desktop repository's published contract.
	 *
	 * Gradle runs the unit-test JVM with the **module** directory as its working
	 * directory — measured, it is `dsh-tabs-android/app` — so `../dsh-tabs` is the
	 * sibling repository. The second spelling is kept because which directory Gradle
	 * picks is a property of the build rather than of this test, and a build that ran
	 * from `app/` instead would otherwise fail for a reason that has nothing to do
	 * with parity.
	 */
	private fun contract(): JSONObject {
		val candidates = listOf(
			java.io.File("../dsh-tabs/docs/contract.json"),
			java.io.File("../../dsh-tabs/docs/contract.json")
		)
		for (candidate in candidates) {
			if (candidate.isFile) {
				return JSONObject(candidate.readBytes().toString(Charsets.UTF_8))
			}
		}
		val stream = javaClass.classLoader?.getResourceAsStream("contract.json")
		assertNotNull(
			"no contract to compare against: neither ${candidates.first().absolutePath} " +
				"nor a contract.json test resource exists. Clone dsh-tabs beside this " +
				"repository, or copy its docs/contract.json into app/src/test/resources/.",
			stream
		)
		return JSONObject(stream!!.readBytes().toString(Charsets.UTF_8))
	}

	/** The two variants the contract captures for each platform. */
	private val variants = listOf("withoutDirectory", "withDirectory")

	/**
	 * A regular expression with cosmetic escaping removed, for comparison.
	 *
	 * Two engines spell the same pattern differently and both are right:
	 * JavaScript's `RegExp.source` keeps an escape that is not required, so the
	 * desktop client writes `http:\/\/`, while Kotlin writes `http:[/]/`. Comparing
	 * the strings byte for byte would report that as drift, and the first version of
	 * this test did exactly that. Comparing them with redundant escapes stripped
	 * still catches a real change — a different character class, a different group —
	 * without failing on a difference no reader of either line could see.
	 */
	private fun normalize(pattern: String): String =
		pattern.replace(Regex("""\\([^\\w])"""), "$1")

	@Test
	fun `the readiness pattern is the one the desktop client parses with`() {
		val readiness = contract().getJSONObject("readiness")
		assertEquals(
			normalize(readiness.getString("linePattern")),
			normalize(Remote.READY_LINE.pattern)
		)
		assertEquals(readiness.getString("lineFlags"), "u")
	}

	@Test
	fun `the readiness reader accepts exactly the documented line`() {
		val readiness = contract().getJSONObject("readiness")
		val line = readiness.getString("example")
		assertEquals(
			"the example line no longer yields the documented URL",
			readiness.getString("exampleUrl"),
			Remote.readyUrl("$line\n")
		)
		// A URL on its own is not a line: the prefix is part of what is matched.
		assertNull(Remote.readyUrl(readiness.getString("exampleUrl") + "\n"))
	}

	@Test
	fun `the three budgets match the desktop client`() {
		val budgets = contract().getJSONObject("budgets")
		assertEquals(budgets.getLong("startTimeoutMs"), Remote.START_TIMEOUT_MS)
		assertEquals(budgets.getLong("probeTimeoutMs"), Remote.PROBE_TIMEOUT_MS)
		assertEquals(budgets.getLong("tunnelTimeoutMs"), Remote.TUNNEL_TIMEOUT_MS)
		assertEquals(budgets.getInt("defaultSshPort"), Remote.DEFAULT_SSH_PORT)
	}

	@Test
	fun `PowerShell CLIXML is decoded rather than pasted into a failure message`() {
		// PowerShell serialises its error stream as CLIXML whenever stderr is
		// redirected, so a Windows remote that fails sends one `<Objs …>` document.
		// This is the real shape, copied from box-b: 1131 bytes over two lines, with
		// the sentence that matters inside an `<S S="Error">` record and its newlines
		// escaped. The desktop client decodes the same text in `readableStderr`, and
		// the two are asserted separately because they are separate implementations.
		val clixml =
			"#< CLIXML\r\n<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">" +
				"<Obj S=\"progress\" RefId=\"0\"><TN RefId=\"0\"><T>System.Object</T></TN>" +
				"<MS><AV>Preparing modules for first use.</AV></MS></Obj>" +
				"<S S=\"Error\">Set-Location : Cannot find path 'C:\\nope' because it does not exist._x000D__x000A_</S>" +
				"<S S=\"Error\">At line:1 char:1108_x000D__x000A_</S>" +
				"<S S=\"Error\">+ ... ; Set-Location -LiteralPath \$dir -ErrorAction Stop; \$argv  ..._x000D__x000A_</S>" +
				"</Objs>"
		val said = Remote.readableStderr(clixml)
		assertTrue("the error record was not recovered: $said", said.contains("Cannot find path"))
		assertTrue("the sentence was truncated: $said", said.contains("does not exist"))
		// The follow-up records are the source excerpt and `CategoryInfo` — for whoever
		// edits the script, not for whoever is trying to connect.
		assertFalse("the source excerpt was pasted in: $said", said.contains("At line:1"))
		assertFalse("the error category was pasted in: $said", said.contains("CategoryInfo"))
		assertFalse("the CLIXML envelope leaked: $said", said.contains("<Objs"))
		assertFalse("the escapes were not decoded: $said", said.contains("_x000D_"))
		assertFalse("the message is not one line: $said", said.contains("\n"))
		assertFalse("a progress record was reported as an error: $said", said.contains("Preparing modules"))
	}

	@Test
	fun `ordinary stderr passes through, and a huge one is bounded`() {
		// A POSIX shell's stderr is already what a reader wants, so it is not mangled.
		assertEquals("bash: dsh: command not found", Remote.readableStderr("bash: dsh: command not found\n"))
		assertEquals("", Remote.readableStderr(""))
		assertEquals("", Remote.readableStderr(null))
		// And a wall of text must not become the whole failure panel.
		val long = Remote.readableStderr("x".repeat(2000))
		assertTrue("not bounded: ${long.length} chars", long.length <= 301)
		assertTrue("the truncation is not visible", long.endsWith("…"))
	}

	@Test
	fun `the POSIX program is byte-identical to the desktop client`() {
		// POSIX is held to exact bytes, because there is no reason for it to differ:
		// the same shell runs the same program on a remote Linux machine whichever
		// client started it.
		val captured = contract().getJSONObject("remoteProgram").getJSONObject("posix")
		for (variant in variants) {
			val expected = captured.getJSONObject(variant)
			val directory = expected.optString("directory").takeIf { it.isNotEmpty() }
			val actual = Remote.posixProgram(directory)
			assertEquals("posix/$variant has drifted from the desktop client", expected.getString("program"), actual)
			assertEquals(
				"posix/$variant remoteCommand has drifted",
				expected.getString("remoteCommand"),
				Remote.remoteCommand(actual, Remote.Platform.POSIX)
			)
		}
	}

	@Test
	fun `the Windows program is byte-identical to the desktop client's`() {
		// **These two used to differ, and this test recorded how.** The desktop client
		// launched the far side's Harness by handing the `.cmd` shim to
		// `Start-Process` with no watchdog. That failed on a real machine — `dsh` exited
		// on a profile it could not resolve, the program reached
		// `[Console]::In.ReadToEnd()` and waited, and the client waited with it for a
		// readiness line that was never coming, so the operator saw a connection that
		// neither finished nor failed. This client resolves `node` itself, logs what it
		// resolved, and watches the child for 60 seconds.
		//
		// The desktop has since adopted all of that, so what was "the shared part plus
		// this client's additions" is now simply one program in two languages — and an
		// equality check is stronger than a fragment list, which can only prove that the
		// pieces somebody thought to name are still present.
		//
		// Nothing here is verified against a real Windows remote: none was reachable.
		// What this proves is that the desktop's copy is the program this client has run
		// for real, byte for byte.
		val captured = contract().getJSONObject("remoteProgram").getJSONObject("windows")
		val desktop = captured.getJSONObject("withoutDirectory").getString("program")
		val ours = Remote.windowsProgram(null)

		// **The two are byte-identical, and that is newer than the fragment list below.**
		//
		// The fragments were written when the desktop client had no watchdog and no
		// resolution of its own, so the two programs genuinely differed and a
		// "shared-plus-additions" check was the honest way to describe it. The desktop
		// has since adopted both, so the difference is gone — and an equality check is
		// stronger than a list of fragments, which can only ever prove that the parts
		// somebody thought to name are still there.
		assertEquals(
			"the Windows program has drifted from the desktop client's",
			desktop,
			ours
		)
		// The directory variant too: it is the one with `Set-Location` and the tilde
		// branch in it, and until this existed the contract captured it, `variants`
		// named it, and nothing compared it.
		val desktopWithDirectory = captured.getJSONObject("withDirectory")
		val oursWithDirectory = Remote.windowsProgram(desktopWithDirectory.getString("directory"))
		assertEquals(
			"the Windows program for a device with a launch directory has drifted",
			desktopWithDirectory.getString("program"),
			oursWithDirectory
		)

		// **No statement boundary may land inside an if/elseif chain.** This is the
		// check that would have caught a bug measured on a real Windows host: the
		// statements are joined with `"; "`, so a chain written as several parts came
		// out as `… { … }; elseif (…) { … }`, and PowerShell ends the statement at that
		// semicolon and then reads `elseif` as a *command name*:
		//
		//   elseif : The term 'elseif' is not recognized as the name of a cmdlet,
		//   function, script file, or operable program.
		//
		// A machine configured `~/...` therefore never had its `~` translated, and
		// nothing noticed because every check looked for fragments like
		// `Join-Path $env:USERPROFILE` — present and correct inside a program that could
		// not run. This asserts the join rather than the content, and the equality check
		// above cannot replace it: both clients could hold the same broken program.
		for (keyword in listOf("elseif", "else", "catch", "finally")) {
			assertFalse(
				"a statement boundary splits a `$keyword` off from its block, which PowerShell reads as a command name",
				Regex("""\}\s*;\s*$keyword\b""").containsMatchIn(oursWithDirectory)
			)
		}
		assertTrue(
			"the tilde chain is gone entirely, so the check above proves nothing",
			oursWithDirectory.contains("if (\$dir -eq '~') { \$dir = \$env:USERPROFILE } elseif (")
		)

		// What the program has to *do*, asserted against ours rather than against the
		// desktop's text. The equality check above would catch a change to either copy,
		// but it says nothing about whether the thing being copied is still correct —
		// and two clients agreeing on a broken program is exactly the failure this
		// whole test exists to make impossible. These are the properties the fragments
		// were standing in for, now named as properties.
		val required = listOf(
			// The teardown contract: stdin is held, and the whole tree is killed rather
			// than the one process, because the Harness is free to spawn under it.
			"[Console]::In.ReadToEnd()",
			"taskkill /PID \$proc.Id /T /F",
			// stdout is inherited, never redirected: a temp file would make the
			// readiness line's readability depend on a file-sharing mode.
			"'web','--no-open','--port','0'",
			// The two additions, each a failure this client paid for.
			"Join-Path \$shimDir 'node.exe'",
			"node_modules\\@deepseek-ai\\dsh\\lib\\bin.js",
			// Whatever the shim's directory holds, the launcher starts the interpreter
			// on `bin.js` or refuses by name. There is no `.cmd` shim fallback left to
			// take: starting the shim is the failure the resolution exists to avoid,
			// and starting nothing would run `node web --no-open --port 0`.
			"\$binJs does not exist",
			"[Console]::Error.WriteLine(\"launch node=",
			"[Diagnostics.Stopwatch]::StartNew()",
			"-lt 60",
			// `dsh` has to be found without a login shell: a non-interactive remote
			// session has no PATH from any rc file.
			"Get-Command dsh",
			"exit 127"
		)
		for (fragment in required) {
			assertTrue("the Windows program no longer contains `$fragment`", ours.contains(fragment))
		}
		assertEquals(
			"the program must not redirect the server's stdout",
			false,
			ours.contains("RedirectStandard")
		)
		assertEquals(
			"windows remoteCommand has drifted",
			Remote.windowsCommand(ours),
			Remote.remoteCommand(ours, Remote.Platform.WINDOWS)
		)
	}

	@Test
	fun `the directory rule is the same on both clients`() {
		val safety = contract().getJSONObject("remoteProgram").getJSONObject("directorySafety")
		val rejected = safety.getJSONArray("rejectedCharacters")
		val characters = (0 until rejected.length()).map { rejected.getString(it) }
		assertTrue("the contract no longer rejects anything", characters.isNotEmpty())
		for (character in characters) {
			assertNotNull(
				"this client accepts `$character`, which the desktop client refuses",
				Remote.directoryProblem("~/work${character}x")
			)
		}
		// A guard on the guard: a rule that refused every path would satisfy the loop
		// above while making the field useless. These are the shapes it is for.
		for (fine in listOf("/srv/dsh", "~/work/dsh", "C:\\work\\dsh", "C:\\Program Files\\dsh")) {
			assertNull("$fine was refused", Remote.directoryProblem(fine))
		}
		assertNull(Remote.directoryProblem(null))
		assertNull(Remote.directoryProblem(""))
	}

	@Test
	fun `a platform is classified the same way from the same probe output`() {
		val platform = contract().getJSONObject("platform")
		assertEquals(platform.getString("probe"), "uname -s")
		assertEquals(
			Remote.Platform.POSIX,
			Remote.classifyPlatform(0, "Linux\n").platform
		)
		assertEquals(
			Remote.Platform.WINDOWS,
			Remote.classifyPlatform(1, "").platform
		)
		// ssh's own failure code is a connection failure, not a platform guess.
		assertTrue(Remote.classifyPlatform(255, "").error)
	}
}
