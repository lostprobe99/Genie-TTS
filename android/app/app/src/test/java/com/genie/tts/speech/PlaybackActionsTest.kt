package com.genie.tts.speech

import com.genie.tts.core.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackActionsTest {
    @Test
    fun sentencePlaybackReportsTheLineAndThenFinishes() {
        val seen = CountDownLatch(1)
        var line = ""
        lateinit var playback: PlaybackActions
        playback = newPlayback { _, _, _, onStarted, _ ->
            onStarted(false)
            line = playback.current().note
            seen.countDown()
        }
        playback.play(listOf(0), listOf(Clip("甲", floatArrayOf(0.2f), 32000, null)))
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        assertTrue(line.contains("声音可能被其他应用占用"))
        assertTrue(line.contains("正在播放第 1 句"))
        waitUntil { playback.current().note == "播放结束" }
        assertFalse(playback.current().playing)
        assertEquals(-1, playback.current().active)
    }

    @Test
    fun stopKeepsTheStoppedLine() {
        val entered = CountDownLatch(1)
        val left = CountDownLatch(1)
        val playback = newPlayback { _, _, stop, _, _ ->
            entered.countDown()
            while (!stop()) Thread.sleep(5)
            left.countDown()
        }
        playback.play(listOf(0), listOf(Clip("甲", floatArrayOf(0.2f), 32000, null)))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals("正在打开播放", playback.current().note)
        assertEquals(0, playback.current().active)
        playback.stop()
        assertTrue(left.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals("已停止", playback.current().note)
        assertFalse(playback.current().playing)
    }

    @Test
    fun quietStopLeavesTheStatusWhenNothingIsPlaying() {
        val playback = newPlayback { _, _, _, _, _ -> }
        playback.play(listOf(0), listOf(Clip("甲", floatArrayOf(0.2f), 32000, null)))
        waitUntil { playback.current().note == "播放结束" }
        playback.quietStop()
        assertEquals("播放结束", playback.current().note)
    }

    @Test
    fun previewShowsTheBannerAndClearsItWhenFinished() {
        var banner = ""
        lateinit var playback: PlaybackActions
        playback = newPlayback { _, _, _, onStarted, _ ->
            onStarted(true)
            banner = playback.current().banner.orEmpty()
        }
        playback.beginPreview("role")
        assertEquals("role", playback.current().previewId)
        assertEquals("正在加载模型", playback.current().banner)
        playback.playPreview("role", floatArrayOf(0.2f, 0.2f), 32000, quiet = false)
        waitUntil { !playback.current().playing && playback.current().previewId == null }
        assertEquals("正在试听 0.0/0.0 秒", banner)
        assertEquals(null, playback.current().banner)
    }

    private fun newPlayback(
        sink: (
            FloatArray,
            Int,
            () -> Boolean,
            (Boolean) -> Unit,
            (Int, Int) -> Unit,
        ) -> Unit,
    ): PlaybackActions {
        return PlaybackActions(sink, store = StateStore(PlaybackState()) { it() })
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) error("状态没有在时间内变成预期的样子")
            Thread.sleep(10)
        }
    }
}
