package com.genie.tts.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

/** [seq] 在这次进程里固定，最旧的行被挤掉后其他行的编号不变。[raw] 是文件里那一行。 */
internal data class LogLine(
    val date: String,
    val time: String,
    val level: LogLevel,
    val module: String,
    val message: String,
    val seq: Long = 0,
    val raw: String = "",
)

private val STAMPED = Regex(
    """^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) (DEBUG|INFO|WARN|ERROR) (\S+) (.*)$""",
)
private val LEGACY = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3})\s+(.*)$""")

/**
 * 应用里唯一的日志。调用方只把行放进内存和队列，`genie-log` 线程批量写文件。
 * 日志页由 [listener] 通知，一段时间内的多行合成一次。
 */
internal object TtsLog {
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 1024 * 1024L
    private const val LOAD_TAIL_BYTES = 512 * 1024
    private const val MAX_TRACE_LINES = 40
    private const val NOTIFY_DELAY_MS = 250L
    private const val CRASH_FLUSH_MS = 1500L
    private const val TAG = "GenieTts"

    private val lock = Any()
    private val lines = ArrayDeque<LogLine>()
    private var nextSeq = 1L
    private var dir: File? = null
    private var store: RollingLogFile? = null
    private val jobs = LinkedBlockingQueue<Job>()
    private val notifyQueued = AtomicBoolean(false)
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    var listener: (() -> Unit)? = null

    private sealed interface Job {
        class Line(val text: String) : Job
        class Task(val done: CountDownLatch?, val block: (RollingLogFile) -> Unit) : Job
    }

    fun attach(target: File) {
        synchronized(lock) {
            if (dir != null) return
            dir = target
            store = RollingLogFile(target, MAX_FILE_BYTES)
            replaceWith(RollingLogFile.tail(target, MAX_LINES, LOAD_TAIL_BYTES))
        }
        Thread(::drain, "genie-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
        catchCrashes()
    }

    /** 带上的 [error] 不是已知的失败种类时，把堆栈也记下来。 */
    fun add(level: LogLevel, module: LogModule, message: String, error: Throwable? = null) {
        put(level, module.label, message)
        val trace = error?.takeIf { it.asFailure() is AppFailure.ModelLoadFailed }
        trace?.let { traceLines(it).forEach { line -> put(level, module.label, line) } }
        Log.println(priority(level), TAG, "${module.label} $message")
        trace?.let { Log.println(priority(level), TAG, Log.getStackTraceString(it)) }
        notifyListener()
    }

    fun entries(): List<LogLine> = synchronized(lock) { lines.toList() }

    fun text(): String = synchronized(lock) {
        if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n") { it.raw }
    }

    fun bytes(): Long = store?.bytes() ?: 0L

    fun path(): String = store?.current?.absolutePath.orEmpty()

    fun isEmpty(): Boolean = synchronized(lock) { lines.isEmpty() } && bytes() == 0L

    /** 两份文件从旧到新写进 [out]。在日志线程上做，写之前先把队列里的行落盘。 */
    fun exportTo(out: OutputStream) {
        var failure: Throwable? = null
        val finished = runOnWriter(TimeUnit.SECONDS.toMillis(10)) { file ->
            try {
                file.copyTo(out)
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        check(finished) { "log writer did not finish" }
    }

    fun reload() {
        val target = dir ?: return
        runOnWriter(CRASH_FLUSH_MS) { it.flush() }
        synchronized(lock) {
            replaceWith(RollingLogFile.tail(target, MAX_LINES, LOAD_TAIL_BYTES))
        }
        notifyListener()
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            nextSeq = 1
            if (store != null) jobs.offer(Job.Task(null) { it.clear() })
        }
        notifyListener()
    }

    private fun put(level: LogLevel, module: String, message: String) {
        val (date, time) = stamp()
        val body = message.replace("\r\n", " ").replace('\n', ' ')
        val raw = "$date $time ${level.name} $module $body"
        synchronized(lock) {
            lines.addLast(LogLine(date, time, level, module, body, nextSeq++, raw))
            while (lines.size > MAX_LINES) lines.removeFirst()
            if (store != null) jobs.offer(Job.Line(raw))
        }
    }

    private fun replaceWith(raw: List<String>) {
        lines.clear()
        nextSeq = 1
        raw.forEach { lines.addLast(parseLogLine(it, nextSeq++)) }
    }

    private fun drain() {
        val file = store ?: return
        val batch = ArrayList<Job>()
        while (true) {
            batch.add(jobs.take())
            jobs.drainTo(batch)
            for (job in batch) {
                try {
                    when (job) {
                        is Job.Line -> file.append(job.text)
                        is Job.Task -> job.block(file)
                    }
                } catch (_: Exception) {
                    runCatching { file.close() }
                } finally {
                    if (job is Job.Task) job.done?.countDown()
                }
            }
            batch.clear()
            runCatching { file.flush() }
        }
    }

    private fun runOnWriter(timeoutMs: Long, block: (RollingLogFile) -> Unit): Boolean {
        if (store == null) return false
        val done = CountDownLatch(1)
        jobs.offer(Job.Task(done, block))
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                put(LogLevel.ERROR, LogModule.APP.label, "闪退 线程 ${thread.name}")
                traceLines(error).forEach { put(LogLevel.ERROR, LogModule.APP.label, it) }
                runOnWriter(CRASH_FLUSH_MS) { it.flush() }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun notifyListener() {
        if (listener == null) return
        if (!notifyQueued.compareAndSet(false, true)) return
        main.postDelayed({
            notifyQueued.set(false)
            listener?.invoke()
        }, NOTIFY_DELAY_MS)
    }

    private fun stamp(): Pair<String, String> {
        val calendar = Calendar.getInstance()
        val date = "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val time = "%02d:%02d:%02d.%03d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND),
        )
        return date to time
    }

    private fun priority(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    private fun traceLines(error: Throwable): List<String> = stackLines(error, MAX_TRACE_LINES)
}

/** 堆栈拆成行，最多 [limit] 行，多出来的记一句还剩几行。 */
internal fun stackLines(error: Throwable, limit: Int): List<String> {
    val all = error.stackTraceToString()
        .lineSequence()
        .map { it.replace("\t", "  ").trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
    if (all.size <= limit) return all
    return all.take(limit) + "  … 还有 ${all.size - limit} 行"
}

internal fun parseLogLine(raw: String, seq: Long = 0): LogLine {
    STAMPED.matchEntire(raw)?.let { match ->
        val level = runCatching { LogLevel.valueOf(match.groupValues[3]) }.getOrDefault(LogLevel.INFO)
        return LogLine(match.groupValues[1], match.groupValues[2], level, match.groupValues[4], match.groupValues[5], seq, raw)
    }
    LEGACY.matchEntire(raw)?.let { match ->
        val message = match.groupValues[2]
        return LogLine("", match.groupValues[1], legacyLevel(message), legacyModule(message), message, seq, raw)
    }
    return LogLine("", "", LogLevel.INFO, "", raw, seq, raw)
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
