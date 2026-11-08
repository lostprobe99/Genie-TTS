package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {
    private val entries = listOf(
        LogLine("2026-11-08", "18:18:36.696", LogLevel.DEBUG, "语音合成", "decoder 60"),
        LogLine("2026-11-08", "18:18:40.685", LogLevel.INFO, "语音合成", "第 1 段 首段等待 6.0 秒"),
        LogLine("2026-11-08", "18:18:46.054", LogLevel.WARN, "语音合成", "播放会空等 0.6 秒"),
        LogLine("2026-11-08", "18:18:53.649", LogLevel.ERROR, "Web", "端口没有打开"),
    )

    @Test
    fun debugStaysHiddenUntilItsChipIsTurnedOn() {
        val shown = shownLogLines(entries, DEFAULT_LOG_LEVELS, "")
        assertEquals(listOf(2, 3, 4), shown.map { it.first })
        assertFalse(LogLevel.DEBUG in DEFAULT_LOG_LEVELS)
        val withDebug = toggleLogLevel(DEFAULT_LOG_LEVELS, LogLevel.DEBUG)
        assertEquals(listOf(1, 2, 3, 4), shownLogLines(entries, withDebug, "").map { it.first })
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
    fun searchStillUsesTheOriginalLineNumber() {
        val shown = shownLogLines(entries, DEFAULT_LOG_LEVELS + LogLevel.DEBUG, "空等")
        assertEquals(3, shown.single().first)
        assertTrue(shown.single().second.message.contains("空等"))
    }
}
