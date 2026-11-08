package com.genie.tts.core

internal val DEFAULT_LOG_LEVELS = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)

internal fun toggleLogLevel(current: Set<LogLevel>, level: LogLevel): Set<LogLevel> =
    if (level in current) current - level else current + level

internal fun shownLogLines(
    entries: List<LogLine>,
    levels: Set<LogLevel>,
    query: String,
): List<Pair<Int, LogLine>> {
    val needle = query.trim()
    return entries.mapIndexed { index, line -> (index + 1) to line }.filter { (_, line) ->
        line.level in levels &&
            (needle.isEmpty() || "${line.module} ${line.message}".contains(needle, ignoreCase = true))
    }
}
