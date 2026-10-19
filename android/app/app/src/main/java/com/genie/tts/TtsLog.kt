package com.genie.tts

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Calendar

internal object TtsLog {
    private const val MAX_LINES = 200
    private const val MAX_BYTES = 256 * 1024
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    var listener: (() -> Unit)? = null

    fun attach(dir: File) {
        val target = File(dir, "tts-log.txt")
        synchronized(lines) {
            if (file == target && lines.isNotEmpty()) return
            file = target
            lines.clear()
            if (!target.isFile) return
            target.readLines().filter { it.isNotBlank() }.takeLast(MAX_LINES).forEach { lines.addLast(it) }
        }
    }

    fun add(message: String) {
        val line = "${stamp()} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            write(line)
        }
        Log.i("GenieTts", message)
        val current = listener ?: return
        Handler(Looper.getMainLooper()).post { current() }
    }

    fun recent(): String {
        return synchronized(lines) { lines.joinToString("\n").ifBlank { "还没有日志" } }
    }

    private fun write(line: String) {
        val target = file ?: return
        try {
            target.appendText(line + "\n")
            if (target.length() <= MAX_BYTES) return
            target.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    private fun stamp(): String {
        val calendar = Calendar.getInstance()
        return "%02d:%02d:%02d.%03d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
        )
    }
}
