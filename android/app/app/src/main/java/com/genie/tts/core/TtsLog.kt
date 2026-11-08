package com.genie.tts.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Calendar

internal enum class LogLevel { DEBUG, INFO, WARN, ERROR }

internal enum class LogModule(val label: String) {
    SERVICE("服务"),
    WEB("Web"),
    SPEECH("语音合成"),
    MONITOR("性能监控"),
    CHARACTER("角色管理"),
    PROSODY("韵律"),
    APP("应用"),
}

internal data class LogLine(
    val date: String,
    val time: String,
    val level: LogLevel,
    val module: String,
    val message: String,
)

private val STAMPED = Regex(
    """^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) (DEBUG|INFO|WARN|ERROR) (\S+) (.*)$""",
)
private val LEGACY = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3})\s+(.*)$""")

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

    fun add(level: LogLevel, module: LogModule, message: String) {
        val line = "${stamp()} ${level.name} ${module.label} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            write(line)
        }
        Log.println(priority(level), "GenieTts", "${module.label} $message")
        notifyListener()
    }

    fun entries(): List<LogLine> {
        return synchronized(lines) { lines.map(::parse) }
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
        return "%04d-%02d-%02d %02d:%02d:%02d.%03d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
        )
    }

    private fun priority(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }
}

private fun parse(raw: String): LogLine {
    STAMPED.matchEntire(raw)?.let { match ->
        val level = runCatching { LogLevel.valueOf(match.groupValues[3]) }.getOrDefault(LogLevel.INFO)
        return LogLine(match.groupValues[1], match.groupValues[2], level, match.groupValues[4], match.groupValues[5])
    }
    LEGACY.matchEntire(raw)?.let { match ->
        val message = match.groupValues[2]
        return LogLine("", match.groupValues[1], legacyLevel(message), legacyModule(message), message)
    }
    return LogLine("", "", LogLevel.INFO, "", raw)
}

private fun legacyLevel(message: String): LogLevel {
    if (message.contains("decoder ")) return LogLevel.DEBUG
    val error = message.contains("失败") ||
        message.contains("failed", ignoreCase = true) ||
        message.contains("没有打开") ||
        message.contains("已被占用") ||
        message.contains("正文长度不对") ||
        Regex("""(?:^|\s)[45]\d{2}(?:\s|$)""").containsMatchIn(message)
    if (error) return LogLevel.ERROR
    val warn = message.contains("空等") ||
        message.contains("不可用") ||
        message.contains("客户端断开") ||
        message.contains("读不到")
    if (warn) return LogLevel.WARN
    return LogLevel.INFO
}

private fun legacyModule(message: String): String {
    return when {
        message.startsWith("请求前") || message.startsWith("请求后") || message.contains("机器读不到") -> "性能监控"
        message.contains("decoder ") || message.startsWith("已加载") ||
            message.startsWith("声码器") || message.startsWith("模型就绪") -> "语音合成"
        else -> "服务"
    }
}
