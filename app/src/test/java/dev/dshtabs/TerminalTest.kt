package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal's pure parts, on the build machine.
 *
 * Everything that needs a phone — a kernel pty, a real shell, xterm.js in a WebView —
 * is verified on a device, as the README's verification round describes. What can be
 * checked here is the half where a mistake is silent: a Ctrl that sends the wrong
 * byte, a UTF-8 character split across two reads, a preset that greys itself out
 * because a probe did not answer.
 */
class TerminalTest {

	// ---------------------------------------------------------------- presets

	@Test
	fun `a preset is a command name, and the shell has none`() {
		val shell = TerminalPresets.ALL.first { it.id == "shell" }
		assertNull("the login shell is the terminal itself", shell.command)
		assertEquals("pi", TerminalPresets.ALL.first { it.id == "pi" }.command)
		assertEquals("opencode", TerminalPresets.ALL.first { it.id == "opencode" }.command)
	}

	@Test
	fun `detection asks each shell family in its own words`() {
		assertEquals("command -v pi", TerminalPresets.detectionCommand("pi", Remote.Platform.POSIX))
		assertEquals("where opencode", TerminalPresets.detectionCommand("opencode", Remote.Platform.WINDOWS))
	}

	@Test
	fun `an unanswered probe leaves the button enabled`() {
		val availability = TerminalPresets.Availability(mapOf("pi" to true))
		val pi = TerminalPresets.ALL.first { it.id == "pi" }
		val opencode = TerminalPresets.ALL.first { it.id == "opencode" }
		assertTrue("answered yes", availability.isEnabled(pi))
		assertTrue("not answered is not absent", availability.isEnabled(opencode))
	}

	@Test
	fun `an answered no is the only thing that disables a preset`() {
		val availability = TerminalPresets.Availability(mapOf("pi" to false, "opencode" to true))
		assertFalse(availability.isEnabled(TerminalPresets.ALL.first { it.id == "pi" }))
		assertTrue(availability.isEnabled(TerminalPresets.ALL.first { it.id == "opencode" }))
	}

	@Test
	fun `the shell is always enabled`() {
		assertTrue(TerminalPresets.Availability(mapOf("shell" to false)).isEnabled(TerminalPresets.ALL[0]))
	}

	// ------------------------------------------------------------------- keys

	@Test
	fun `ctrl turns a letter into its control byte`() {
		assertEquals("\u0003", TerminalKeys.withCtrl("c"))
		assertEquals("\u0003", TerminalKeys.withCtrl("C"))
		assertEquals("\u0001", TerminalKeys.withCtrl("a"))
		assertEquals("\u001a", TerminalKeys.withCtrl("z"))
	}

	@Test
	fun `ctrl has the punctuation mappings a shell expects`() {
		assertEquals("\u0000", TerminalKeys.withCtrl(" "))
		assertEquals("\u001b", TerminalKeys.withCtrl("["))
		assertEquals("\u001c", TerminalKeys.withCtrl("\\"))
		assertEquals("\u001d", TerminalKeys.withCtrl("]"))
		assertEquals("\u001e", TerminalKeys.withCtrl("^"))
		assertEquals("\u001f", TerminalKeys.withCtrl("_"))
	}

	@Test
	fun `ctrl leaves anything that is not one key alone`() {
		assertNull(TerminalKeys.withCtrl("ab"))
		assertNull(TerminalKeys.withCtrl(""))
		assertNull(TerminalKeys.withCtrl("\u001b[A"))
		assertNull(TerminalKeys.withCtrl("é"))
	}

	@Test
	fun `the arrows are the sequences xterm sends`() {
		assertEquals("\u001b[A", TerminalKeys.UP)
		assertEquals("\u001b[B", TerminalKeys.DOWN)
		assertEquals("\u001b[C", TerminalKeys.RIGHT)
		assertEquals("\u001b[D", TerminalKeys.LEFT)
		assertEquals("\t", TerminalKeys.TAB)
		assertEquals("\u001b", TerminalKeys.ESC)
	}

	@Test
	fun `a pty size is clamped to something a shell can lay out`() {
		assertEquals(TerminalKeys.DEFAULT_COLS, TerminalKeys.clampCols(TerminalKeys.DEFAULT_COLS))
		assertEquals(TerminalKeys.MIN_COLS, TerminalKeys.clampCols(0))
		assertEquals(TerminalKeys.MAX_COLS, TerminalKeys.clampCols(100_000))
		assertEquals(TerminalKeys.MIN_ROWS, TerminalKeys.clampRows(-3))
		assertEquals(TerminalKeys.MAX_ROWS, TerminalKeys.clampRows(9_999))
	}

	// ------------------------------------------------------------------- text

	@Test
	fun `ascii crosses a chunk boundary unchanged`() {
		val chunker = Utf8Chunker()
		assertEquals("hello ", chunker.decode("hello ".toByteArray()))
		assertEquals("world", chunker.decode("world".toByteArray()))
	}

	@Test
	fun `a multi-byte character split between chunks is held, not mangled`() {
		val chunker = Utf8Chunker()
		val bytes = "中文".toByteArray(Charsets.UTF_8)
		// Three bytes per character; split inside the first one.
		val first = chunker.decode(bytes, 2)
		assertEquals("nothing complete yet", "", first)
		val rest = chunker.decode(bytes.copyOfRange(2, bytes.size), bytes.size - 2)
		assertEquals("中文", rest)
	}

	@Test
	fun `a four-byte emoji survives the same treatment`() {
		val chunker = Utf8Chunker()
		val bytes = "ok 🚀 done".toByteArray(Charsets.UTF_8)
		val builder = StringBuilder()
		// Deliberately ugly chunk sizes, the way a pty actually delivers bytes.
		var offset = 0
		for (size in intArrayOf(1, 2, 3, 1, 5, 100)) {
			if (offset >= bytes.size) break
			val end = minOf(offset + size, bytes.size)
			builder.append(chunker.decode(bytes.copyOfRange(offset, end), end - offset))
			offset = end
		}
		if (offset < bytes.size) builder.append(chunker.decode(bytes.copyOfRange(offset, bytes.size), bytes.size - offset))
		assertEquals("ok 🚀 done", builder.toString())
	}

	@Test
	fun `invalid bytes are decoded rather than stalling the stream`() {
		val chunker = Utf8Chunker()
		val text = chunker.decode(byteArrayOf(0x61, 0x80.toByte(), 0x62))
		assertTrue("a byte of output", text.startsWith("a"))
		assertTrue("the valid tail survives", text.endsWith("b"))
	}

	@Test
	fun `a stream of continuation bytes does not grow without bound`() {
		val chunker = Utf8Chunker()
		repeat(32) { chunker.decode(byteArrayOf(0x80.toByte())) }
		// The chunker drops an impossible partial sequence instead of holding it for ever;
		// the next valid text still decodes normally.
		assertEquals("ok", chunker.decode("ok".toByteArray()))
	}

	@Test
	fun `javascript results come back as strings and nothing else`() {
		assertEquals("hello", TerminalView.decodeJavascriptString("\"hello\""))
		assertEquals("a\nb", TerminalView.decodeJavascriptString("\"a\\nb\""))
		assertEquals("中文", TerminalView.decodeJavascriptString("\"中文\""))
		assertNull(TerminalView.decodeJavascriptString("null"))
		assertNull(TerminalView.decodeJavascriptString(""))
		assertNull(TerminalView.decodeJavascriptString(null))
	}

	// ------------------------------------------------------------------ state

	@Test
	fun `a terminal that was never opened is not alive`() {
		val device = Device.create("box", "127.0.0.1", "me", 22, null)
		val terminal = TerminalSession(device, Credential.Password("secret"))
		assertEquals(TerminalSession.State.CONNECTING, terminal.state)
		assertFalse(terminal.isAlive)
	}
}
