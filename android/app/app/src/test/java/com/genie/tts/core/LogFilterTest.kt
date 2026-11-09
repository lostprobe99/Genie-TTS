package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {
    private val entries = listOf(
        LogLine("2026-11-08", "18:18:36.696", LogLevel.DEBUG, "语音合成", "decoder 60", seq = 11),
        LogLine("2026-11-08", "18:18:40.685", LogLevel.INFO, "语音合成", "第 1 段 首段等待 6.0 秒", seq = 12),
        LogLine("2026-11-08", "18:18:46.054", LogLevel.WARN, "语音合成", "播放会空等 0.6 秒", seq = 13),
        LogLine("2026-11-08", "18:18:53.649", LogLevel.ERROR, "Web", "端口没有打开", seq = 14),
    )

    @Test
    fun debugStaysHiddenUntilItsChipIsTurnedOn() {
        val shown = shownLogLines(entries, DEFAULT_LOG_LEVELS, "")
        assertEquals(listOf(12L, 13L, 14L), shown.map { it.seq })
        assertFalse(LogLevel.DEBUG in DEFAULT_LOG_LEVELS)
        val withDebug = toggleLogLevel(DEFAULT_LOG_LEVELS, LogLevel.DEBUG)
        assertEquals(listOf(11L, 12L, 13L, 14L), shownLogLines(entries, withDebug, "").map { it.seq })
    }

    @Test
    fun chipsToggleWithoutTurningTheOthersOff() {
        val withoutInfo = toggleLogLevel(DEFAULT_LOG_LEVELS, LogLevel.INFO)
        assertFalse(LogLevel.INFO in withoutInfo)
        assertTrue(LogLevel.WARN in withoutInfo)
        assertTrue(LogLevel.ERROR in withoutInfo)
        assertEquals(DEFAULT_LOG_LEVELS, toggleLogLevel(withoutInfo, LogLevel.INFO))
    }

    @Test
    fun searchKeepsTheLineNumber() {
        val shown = shownLogLines(entries, DEFAULT_LOG_LEVELS + LogLevel.DEBUG, "空等")
        assertEquals(13L, shown.single().seq)
        assertTrue(shown.single().message.contains("空等"))
    }

    @Test
    fun newLinesCountOnlyWhatTheFiltersWouldShow() {
        assertEquals(2, newShownLines(entries, DEFAULT_LOG_LEVELS, "", after = 12))
        assertEquals(1, newShownLines(entries, DEFAULT_LOG_LEVELS, "端口", after = 12))
        assertEquals(0, newShownLines(entries, DEFAULT_LOG_LEVELS, "", after = 14))
    }

    @Test
    fun copyKeepsTheNewestLinesInOrder() {
        val lines = (1..5).map { parseLogLine("2026-11-09 10:00:0$it.000 INFO 服务 第 $it 行", it.toLong()) }
        val (all, allCount) = recentLogText(lines, 10_000)
        assertEquals(5, allCount)
        assertEquals(lines.joinToString("\n", postfix = "\n") { it.raw }, all)
        val oneLine = lines.first().raw.length + 1
        val (recent, count) = recentLogText(lines, oneLine * 2)
        assertEquals(2, count)
        assertEquals(lines.takeLast(2).joinToString("\n", postfix = "\n") { it.raw }, recent)
    }
}
