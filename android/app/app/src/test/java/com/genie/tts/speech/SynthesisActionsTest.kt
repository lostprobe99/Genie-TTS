package com.genie.tts.speech

import com.genie.tts.character.CharacterInfo
import com.genie.tts.core.LogSink
import com.genie.tts.core.LogLevel
import com.genie.tts.core.StateStore
import com.genie.tts.core.StepMark
import com.genie.tts.core.TaskLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SynthesisActionsTest {
    @Test
    fun synthesizeFillsClipsAndReleasesTheLane() {
        val lane = lane()
        val entered = CountDownLatch(1)
        val playback = PlaybackActions(
            sink = { _, _, stop, _, _ ->
                entered.countDown()
                while (!stop()) Thread.sleep(5)
            },
            store = StateStore(PlaybackState()) { it() },
        )
        playback.play(listOf(0), listOf(Clip("旧", floatArrayOf(0.2f), 32000, null)))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val marker = MemoryMark()
        val logs = mutableListOf<String>()
        val clock = AtomicLong(1_000)
        val actions = actions(
            lane = lane,
            playback = playback,
            marker = marker,
            clock = { clock.get() },
            log = LogSink { _, message, _ -> logs += message },
            synthesize = { _, pieces, _, _, onMark, onPiece, _ ->
                assertEquals("1.0（1）", marker.armed)
                onMark(true, -1)
                onMark(false, 0)
                clock.set(1_800)
                val clip = Clip(pieces.single(), floatArrayOf(0.2f), 32000, null)
                onPiece(0, clip)
                listOf(clip)
            },
        )
        actions.synthesize(character(), listOf("你好"), 1.0, "1.0（1）")
        val state = actions.current()
        assertFalse(lane.isBusy())
        assertFalse(state.synthesizing)
        assertEquals("合成完成，1 句可播放", state.note)
        assertEquals("你好", state.clips.single()?.text)
        assertEquals(800L, state.elapsedMs)
        assertEquals("", playback.current().note)
        assertFalse(playback.current().playing)
        assertNull(marker.armed)
        assertTrue(logs.any { it.contains("主屏开始 a 1句 语速 1.00") })
        assertTrue(logs.any { it.contains("主屏完成 a 1/1句可播放") })
    }

    @Test
    fun aBusyLaneRefusesAnotherSynthesis() {
        val lane = lane()
        lane.occupy()
        var called = false
        val actions = actions(lane = lane, synthesize = { _, _, _, _, _, _, _ ->
            called = true
            emptyList()
        })
        actions.synthesize(character(), listOf("你好"), 1.0, "1.0（1）")
        assertFalse(called)
        assertTrue(actions.current().clips.isEmpty())
    }

    @Test
    fun failureClearsTheClipsAndRaisesAnAlert() {
        val lane = lane()
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val actions = actions(
            lane = lane,
            log = LogSink { level, message, _ -> logs += level to message },
            synthesize = { _, _, _, _, _, _, _ -> error("模型打不开") },
        )
        actions.synthesize(character(), listOf("你好"), 1.0, "1.0（1）")
        val state = actions.current()
        assertFalse(lane.isBusy())
        assertTrue(state.clips.isEmpty())
        assertEquals("模型打不开", state.alert)
        assertTrue(logs.any { it.first == LogLevel.ERROR && it.second.contains("主屏合成失败 a") })
    }

    @Test
    fun cancelDoesNotAlert() {
        lateinit var actions: SynthesisActions
        val lane = lane()
        actions = actions(lane = lane, synthesize = { _, _, _, _, _, _, stop ->
            actions.cancel()
            check(stop())
            throw SynthesisCancelled()
        })
        actions.synthesize(character(), listOf("你好"), 1.0, "1.0（1）")
        assertNull(actions.current().alert)
        assertFalse(actions.current().synthesizing)
        assertFalse(lane.isBusy())
    }

    @Test
    fun previewReleasesTheLaneBeforePlaybackFinishes() {
        val lane = lane()
        val playback = playback()
        val played = mutableListOf<String>()
        playback.listen { state ->
            if (state.playing) state.previewId?.let { played += it }
        }
        val previewing = actions(
            lane = lane,
            playback = playback,
            preview = { character, _, onMark, _ ->
                onMark(false, 0)
                Clip(character.refText, floatArrayOf(0.2f), 32000, null)
            },
        )
        previewing.preview(character(), "1.0（1）")
        assertFalse(lane.isBusy())
        assertTrue(played.contains("a"))
        waitUntil { !playback.current().playing }
    }

    @Test
    fun previewFailureClearsTheBannerAndAlerts() {
        val lane = lane()
        val playback = playback()
        val actions = actions(
            lane = lane,
            playback = playback,
            preview = { _, _, _, _ -> Clip("参考", null, 32000, "这句没有读出来") },
        )
        actions.preview(character(), "1.0（1）")
        assertFalse(lane.isBusy())
        assertNull(playback.current().previewId)
        assertEquals("这句没有读出来", playback.current().alert)
        assertNull(playback.current().banner)
    }

    @Test
    fun anInProgressSynthesisDoesNotConsumeTheMarker() {
        val marker = MemoryMark()
        marker.consumeValue = "9（9）"
        lateinit var actions: SynthesisActions
        actions = actions(marker = marker, synthesize = { _, _, _, _, _, _, _ ->
            assertNull(actions.consumeMarker())
            assertEquals("9（9）", marker.consumeValue)
            emptyList()
        })
        actions.synthesize(character(), listOf("你好"), 1.0, "1.0（1）")
        assertEquals("9（9）", actions.consumeMarker())
    }

    private fun character() = CharacterInfo("a", "甲", File("a"), "参考")

    private fun lane() = TaskLane(StateStore(false) { it() })

    private fun playback() = PlaybackActions(
        sink = { _, _, _, _, _ -> },
        store = StateStore(PlaybackState()) { it() },
    )

    private fun actions(
        lane: TaskLane = lane(),
        playback: PlaybackActions = playback(),
        marker: StepMark = MemoryMark(),
        clock: () -> Long = { 0L },
        log: LogSink = LogSink { _, _, _ -> },
        synthesize: (
            CharacterInfo,
            List<String>,
            Double,
            Int,
            (Boolean, Int) -> Unit,
            (Int, Clip) -> Unit,
            () -> Boolean,
        ) -> List<Clip> = { _, _, _, _, _, _, _ -> emptyList() },
        preview: (
            CharacterInfo,
            Int,
            (Boolean, Int) -> Unit,
            () -> Boolean,
        ) -> Clip = { _, _, _, _ -> Clip("参考", floatArrayOf(0.2f), 32000, null) },
    ): SynthesisActions {
        return SynthesisActions(
            lane = lane,
            playback = playback,
            marker = marker,
            synthesizePieces = synthesize,
            previewPiece = preview,
            clock = clock,
            threads = { 1 },
            log = log,
            runAsync = { it() },
            post = { it() },
        )
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) error("状态没有在时间内变成预期的样子")
            Thread.sleep(10)
        }
    }

    private class MemoryMark : StepMark {
        var armed: String? = null
        var consumeValue: String? = null
        override fun arm(label: String) {
            armed = label
        }

        override fun disarm() {
            armed = null
        }

        override fun consume(): String? = consumeValue.also { consumeValue = null }
    }
}
