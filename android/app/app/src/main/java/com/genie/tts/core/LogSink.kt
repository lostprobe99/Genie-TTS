package com.genie.tts.core

/** 一个模块往日志里写。测试换成收集到列表里。 */
internal fun interface LogSink {
    fun write(level: LogLevel, message: String, error: Throwable?)
}

internal operator fun LogSink.invoke(level: LogLevel, message: String, error: Throwable? = null) =
    write(level, message, error)

internal fun moduleLog(module: LogModule): LogSink = LogSink { level, message, error ->
    TtsLog.add(level, module, message, error)
}
