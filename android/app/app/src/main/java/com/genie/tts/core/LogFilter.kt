package com.genie.tts.core

internal val DEFAULT_LOG_LEVELS = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)

internal fun toggleLogLevel(current: Set<LogLevel>, level: LogLevel): Set<LogLevel> =
    if (level in current) current - level else current + level

internal fun shownLogLines(
    entries: List<LogLine>,
    levels: Set<LogLevel>,
    query: String,
): List<LogLine> {
    val needle = query.trim()
    return entries.filter { line ->
        line.level in levels &&
            (needle.isEmpty() || "${line.module} ${line.message}".contains(needle, ignoreCase = true))
    }
}

/** 停在旧位置时，[after] 之后新来的、又符合筛选的行数。 */
internal fun newShownLines(entries: List<LogLine>, levels: Set<LogLevel>, query: String, after: Long): Int =
    shownLogLines(entries.filter { it.seq > after }, levels, query).size

/** 复制时从最新往回取，总字数不超过 [maxChars]，顺序仍是从旧到新。 */
internal fun recentLogText(entries: List<LogLine>, maxChars: Int): Pair<String, Int> {
    var total = 0
    var taken = 0
    for (line in entries.asReversed()) {
        val size = line.raw.length + 1
        if (taken > 0 && total + size > maxChars) break
        total += size
        taken += 1
    }
    val text = entries.takeLast(taken).joinToString("\n", postfix = if (taken > 0) "\n" else "") { it.raw }
    return text to taken
}
