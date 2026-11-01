package com.genie.tts

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Calendar

internal data class LogLine(val time: String, val message: String)

internal enum class LogLevel { INFO, WARN, ERROR, DEBUG }

internal fun logLevel(message: String): LogLevel {
    if (message.contains("decoder ")) return LogLevel.DEBUG
    val error = message.contains("失败") ||
        message.contains("failed", ignoreCase = true) ||
        message.contains("没有打开") ||
        message.contains("已被占用") ||
        message.contains("正文长度不对") ||
        STATUS.containsMatchIn(message)
    if (error) return LogLevel.ERROR
    val warn = message.contains("空等") ||
        message.contains("不可用") ||
        message.contains("客户端断开") ||
        message.contains("读不到")
    if (warn) return LogLevel.WARN
    return LogLevel.INFO
}

internal fun logTag(message: String): String {
    return when {
        message.startsWith("请求前") || message.startsWith("请求后") || message.contains("机器读不到") -> "机器"
        message.contains("decoder ") || message.startsWith("已加载") ||
            message.startsWith("声码器") || message.startsWith("模型就绪") -> "模型"
        else -> "接口"
    }
}

private val STAMP = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3})\s+(.*)$""")
private val STATUS = Regex("""(?:^|\s)[45]\d{2}(?:\s|$)""")

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
            load(target)
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
        notifyListener()
    }

    fun entries(): List<LogLine> {
        return synchronized(lines) {
            lines.map { raw ->
                val match = STAMP.matchEntire(raw)
                if (match == null) LogLine("", raw) else LogLine(match.groupValues[1], match.groupValues[2])
            }
        }
    }

    fun text(): String {
        return synchronized(lines) {
            if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        }
    }

    fun bytes(): Long {
        return synchronized(lines) { file?.takeIf { it.isFile }?.length() ?: 0L }
    }

    fun path(): String {
        return synchronized(lines) { file?.absolutePath.orEmpty() }
    }

    fun reload() {
        synchronized(lines) {
            val target = file ?: return
            load(target)
        }
    }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            try {
                file?.writeText("")
            } catch (_: Exception) {
            }
        }
        notifyListener()
    }

    private fun load(target: File) {
        lines.clear()
        if (!target.isFile) return
        target.readLines().filter { it.isNotBlank() }.takeLast(MAX_LINES).forEach { lines.addLast(it) }
    }

    private fun notifyListener() {
        val current = listener ?: return
        Handler(Looper.getMainLooper()).post { current() }
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
