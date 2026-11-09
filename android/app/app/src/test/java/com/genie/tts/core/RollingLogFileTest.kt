package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class RollingLogFileTest {
    private val dir: File = Files.createTempDirectory("genie-log").toFile()

    private fun line(n: Int) = "2026-11-09 10:00:00.000 INFO 服务 第 $n 行"

    @Test
    fun fullFileMovesToThePreviousOne() {
        val limit = (line(1).toByteArray().size + 1L) * 3
        val file = RollingLogFile(dir, limit)
        (1..5).forEach { file.append(line(it)) }
        file.flush()
        assertEquals((1..3).map(::line), file.previous.readLines())
        assertEquals((4..5).map(::line), file.current.readLines())
        assertTrue(file.current.length() <= limit)

        (6..8).forEach { file.append(line(it)) }
        file.flush()
        assertEquals((4..6).map(::line), file.previous.readLines())
        assertEquals((7..8).map(::line), file.current.readLines())
    }

    @Test
    fun tailReadsBothFilesOldestFirst() {
        val file = RollingLogFile(dir, (line(1).toByteArray().size + 1L) * 3)
        (1..5).forEach { file.append(line(it)) }
        file.close()
        assertEquals((2..5).map(::line), RollingLogFile.tail(dir, maxLines = 4, maxBytes = 4096))
        assertEquals((4..5).map(::line), RollingLogFile.tail(dir, maxLines = 2, maxBytes = 4096))
    }

    @Test
    fun tailDropsTheCutLineAtTheStart() {
        val file = RollingLogFile(dir, 1 shl 20)
        (1..10).forEach { file.append(line(it)) }
        file.close()
        val size = line(1).toByteArray().size + 1
        val tail = RollingLogFile.tail(dir, maxLines = 100, maxBytes = size * 3 + 5)
        assertEquals((8..10).map(::line), tail)
    }

    @Test
    fun exportWritesPreviousThenCurrent() {
        val file = RollingLogFile(dir, (line(1).toByteArray().size + 1L) * 2)
        (1..3).forEach { file.append(line(it)) }
        val out = ByteArrayOutputStream()
        file.copyTo(out)
        assertEquals((1..3).joinToString("") { line(it) + "\n" }, out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun clearRemovesBothFilesAndKeepsWriting() {
        val file = RollingLogFile(dir, (line(1).toByteArray().size + 1L) * 2)
        (1..3).forEach { file.append(line(it)) }
        file.clear()
        assertFalse(file.previous.exists())
        assertFalse(file.current.exists())
        file.append(line(9))
        file.flush()
        assertEquals(listOf(line(9)), file.current.readLines())
    }

    @Test
    fun longTracesAreCut() {
        val error = IllegalStateException("坏了")
        error.stackTrace = Array(60) { StackTraceElement("Demo", "step$it", "Demo.kt", it) }
        val lines = stackLines(error, 10)
        assertEquals(11, lines.size)
        assertEquals("java.lang.IllegalStateException: 坏了", lines.first())
        assertTrue(lines.last().contains("还有 51 行"))
    }
}
