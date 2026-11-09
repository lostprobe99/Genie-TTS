package com.genie.tts.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Writer

/**
 * `tts-log.txt` 和上一份 `tts-log.1.txt`。当前这份写满 [maxBytes] 就改名成上一份，再开新的。
 * 只在日志线程上调用；[tail] 例外，它只读文件。
 */
internal class RollingLogFile(private val dir: File, private val maxBytes: Long) {
    val current: File get() = File(dir, CURRENT)
    val previous: File get() = File(dir, PREVIOUS)

    private var writer: Writer? = null
    private var size = 0L

    fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
        val out = writer ?: open()
        if (size > 0 && size + bytes > maxBytes) {
            rotate()
            return append(line)
        }
        out.write(line)
        out.write("\n")
        size += bytes
    }

    fun flush() {
        writer?.flush()
    }

    fun clear() {
        close()
        current.delete()
        previous.delete()
        size = 0
    }

    fun close() {
        try {
            writer?.close()
        } finally {
            writer = null
        }
    }

    fun bytes(): Long = previous.length() + current.length()

    /** 先上一份、再当前这份，原样写出。 */
    fun copyTo(out: OutputStream) {
        flush()
        for (file in listOf(previous, current)) {
            if (file.isFile) file.inputStream().use { it.copyTo(out) }
        }
    }

    private fun open(): Writer {
        dir.mkdirs()
        size = current.length()
        return BufferedWriter(OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8))
            .also { writer = it }
    }

    private fun rotate() {
        close()
        previous.delete()
        current.renameTo(previous)
        size = 0
        open()
    }

    companion object {
        const val CURRENT = "tts-log.txt"
        const val PREVIOUS = "tts-log.1.txt"

        /** 两份文件末尾最多 [maxLines] 行，从旧到新。每份最多读末尾 [maxBytes]，不会整份读进来。 */
        fun tail(dir: File, maxLines: Int, maxBytes: Int): List<String> {
            val newer = tailOf(File(dir, CURRENT), maxBytes)
            if (newer.size >= maxLines) return newer.takeLast(maxLines)
            val older = tailOf(File(dir, PREVIOUS), maxBytes)
            return (older + newer).takeLast(maxLines)
        }

        private fun tailOf(file: File, maxBytes: Int): List<String> {
            if (!file.isFile) return emptyList()
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = (length - maxBytes).coerceAtLeast(0L)
                val buffer = ByteArray((length - start).toInt())
                raf.seek(start)
                raf.readFully(buffer)
                val lines = String(buffer, Charsets.UTF_8).split('\n')
                val whole = if (start > 0) lines.drop(1) else lines
                return whole.map { it.trimEnd('\r') }.filter { it.isNotBlank() }
            }
        }
    }
}
