package dev.dshtabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The connection log's file: append, read back, and the trim that keeps it bounded.
 *
 * The trim is the part worth pinning. It runs only once the file is already over its
 * bound, it must keep the **newest** end (that is where the failure is), and it must
 * not corrupt the file when it rewrites it. A log that grows without bound on a phone
 * is a log that gets the app reported for storage use; a log that trims the wrong end
 * is a log that is empty exactly when it is needed.
 */
class DiagFileTest {

	private fun target(): File {
		val directory = Files.createTempDirectory("diag-file").toFile()
		directory.deleteOnExit()
		return File(File(directory, "diag"), "connection.log")
	}

	@Test
	fun `the newest lines come back in order`() {
		val log = DiagFile(target())
		log.append("a")
		log.append("b")
		log.append("c")
		assertEquals(listOf("a", "b", "c"), log.tail(10))
		assertEquals(listOf("b", "c"), log.tail(2))
	}

	@Test
	fun `a log that was never written is empty, not an error`() {
		val log = DiagFile(target())
		assertTrue(log.tail(5).isEmpty())
	}

	@Test
	fun `the file trims itself and keeps the newest end`() {
		val file = target()
		// Small bounds so the test is a few appends long rather than a few thousand.
		val log = DiagFile(file, maxBytes = 2_000, keepBytes = 800)
		val padding = "x".repeat(60)
		repeat(80) { index -> log.append("${index.toString().padStart(3, '0')} $padding") }
		val kept = log.tail(1_000)
		assertTrue("the file has to stay bounded, was ${file.length()}", file.length() <= 2_000 + 80)
		assertTrue("the newest line has to survive a trim", kept.last().startsWith("079 "))
		assertTrue("the oldest lines have to be gone", kept.none { it.startsWith("000 ") })
		// A trim that loses lines from the middle would leave a gap a reader could not
		// tell from a quiet period.
		val numbers = kept.map { it.substringBefore(' ').toInt() }
		val gaps = numbers.zipWithNext().filter { (earlier, later) -> later != earlier + 1 }
		assertTrue("a trim has to keep a contiguous run, gaps at $gaps", gaps.isEmpty())
	}

	@Test
	fun `a non-ASCII line survives the trim`() {
		val file = target()
		val log = DiagFile(file, maxBytes = 400, keepBytes = 200)
		repeat(20) { index -> log.append("$index 中文字段 box-a") }
		val kept = log.tail(20)
		assertTrue(kept.isNotEmpty())
		assertTrue("UTF-8 content must round-trip", kept.last().endsWith(" 中文字段 box-a"))
	}
}
