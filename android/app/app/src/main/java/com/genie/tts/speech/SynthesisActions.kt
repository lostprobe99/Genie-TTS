package com.genie.tts.speech

import com.genie.tts.character.CharacterInfo
import com.genie.tts.core.LogLevel
import com.genie.tts.core.LogModule
import com.genie.tts.core.StateStore
import com.genie.tts.core.StepMark
import com.genie.tts.core.TaskLane
import com.genie.tts.core.TtsLog
import com.genie.tts.core.uiText
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 主屏合成这一刻的状态。试听不改这里的分句。 */
internal data class SynthesisState(
    val clips: List<Clip?> = emptyList(),
    val synthesizing: Boolean = false,
    val loadingModel: Boolean = false,
    val active: Int = -1,
    val note: String = "",
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val run: Long = 0L,
    val alert: String? = null,
    val alertId: Long = 0,
)

internal fun synthesisSummary(clips: List<Clip?>): String {
    val playable = clips.count { it?.samples != null }
    val quiet = clips.count { clip -> clip?.samples?.let(::isQuiet) == true }
    return when {
        playable == 0 -> "没有可以播放的句子"
        quiet == playable -> "合成完成，但是几乎没有声音"
        else -> "合成完成，$playable 句可播放"
    }
}

/**
 * 主屏合成和试听的发起。播放交给 [PlaybackActions]。
 * 界面上已经有别的耗时事情时，这次直接放弃。
 */
internal class SynthesisActions(
    private val lane: TaskLane,
    private val playback: PlaybackActions,
    private val marker: StepMark,
    private val synthesizePieces: (
        character: CharacterInfo,
        pieces: List<String>,
        rate: Double,
        threads: Int,
        onMark: (Boolean, Int) -> Unit,
        onPiece: (Int, Clip) -> Unit,
        stop: () -> Boolean,
    ) -> List<Clip>,
    private val previewPiece: (
        character: CharacterInfo,
        threads: Int,
        onMark: (Boolean, Int) -> Unit,
        stop: () -> Boolean,
    ) -> Clip,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val threads: () -> Int = { inferenceThreads() },
    private val log: (LogLevel, String) -> Unit = { level, message ->
        TtsLog.add(level, LogModule.SPEECH, message)
    },
    private val runAsync: (() -> Unit) -> Unit = { task ->
        executor.execute(task)
    },
    post: (() -> Unit) -> Unit = { block ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    },
) {
    private val store = StateStore(SynthesisState(), post)
    private val runs = AtomicLong(0)
    private val cancel = AtomicReference<CancelHandle?>(null)

    fun current(): SynthesisState = store.current()

    fun listen(listener: (SynthesisState) -> Unit): () -> Unit = store.listen(listener)

    fun cancel() {
        cancel.get()?.cancel()
    }

    /** 正在合成时不读。读过就删掉，避免下次打开再提示一次。 */
    fun consumeMarker(): String? {
        if (store.current().synthesizing) return null
        return marker.consume()
    }

    fun clear() {
        store.update { SynthesisState(alertId = it.alertId) }
    }

    fun synthesize(character: CharacterInfo, pieces: List<String>, rate: Double, versionLabel: String) {
        if (pieces.isEmpty()) return
        if (!lane.tryOccupy()) return
        val run = runs.incrementAndGet()
        val handle = CancelHandle()
        cancel.set(handle)
        playback.quietStop()
        playback.hideBanner()
        store.update {
            SynthesisState(
                clips = pieces.map { Clip(it, null, 32000, null) },
                synthesizing = true,
                loadingModel = true,
                active = 0,
                note = "正在加载模型，共 ${pieces.size} 句",
                startedAt = clock(),
                run = run,
                alertId = it.alertId,
            )
        }
        marker.arm(versionLabel)
        runAsync {
            try {
                log(LogLevel.INFO, "主屏开始 ${character.id} ${pieces.size}句 语速 ${"%.2f".format(Locale.US, rate)}")
                try {
                    val made = synthesizePieces(
                        character,
                        pieces,
                        rate,
                        threads(),
                        { loading, index ->
                            if (runs.get() != run) return@synthesizePieces
                            store.update { state ->
                                if (state.run != run) state
                                else state.copy(
                                    loadingModel = loading,
                                    active = if (!loading && index in state.clips.indices) index else state.active,
                                    note = if (loading) {
                                        "正在加载模型，共 ${pieces.size} 句"
                                    } else {
                                        "正在合成第 ${index + 1} 句，共 ${pieces.size} 句"
                                    },
                                )
                            }
                        },
                        { index, clip ->
                            if (runs.get() != run) return@synthesizePieces
                            store.update { state ->
                                if (state.run != run) state
                                else {
                                    val clips = state.clips.toMutableList()
                                    if (index in clips.indices) clips[index] = clip
                                    state.copy(clips = clips)
                                }
                            }
                        },
                        { handle.isCancelled() },
                    )
                    if (runs.get() != run) return@runAsync
                    logHome(character.id, made)
                    store.update { state ->
                        if (state.run != run) state
                        else state.copy(
                            clips = made,
                            synthesizing = false,
                            loadingModel = false,
                            active = -1,
                            startedAt = 0,
                            elapsedMs = if (state.startedAt > 0L) clock() - state.startedAt else state.elapsedMs,
                            note = synthesisSummary(made),
                        )
                    }
                } catch (error: Exception) {
                    if (error !is SynthesisCancelled && runs.get() == run) {
                        val detail = error.message?.trim().orEmpty().ifBlank { "失败" }
                        log(LogLevel.ERROR, "主屏合成失败 ${character.id} $detail")
                        store.update { state ->
                            if (state.run != run) state
                            else SynthesisState(alert = uiText(error), alertId = state.alertId + 1)
                        }
                    }
                }
            } finally {
                marker.disarm()
                if (runs.get() == run) {
                    store.update { it.copy(synthesizing = false, loadingModel = false) }
                    lane.release()
                }
                if (cancel.get() === handle) cancel.set(null)
            }
        }
    }

    fun preview(character: CharacterInfo, versionLabel: String) {
        if (!lane.tryOccupy()) return
        val run = runs.incrementAndGet()
        val handle = CancelHandle()
        cancel.set(handle)
        playback.beginPreview(character.id)
        marker.arm(versionLabel)
        var release = true
        runAsync {
            try {
                log(LogLevel.INFO, "试听 ${character.id}")
                val clip = try {
                    previewPiece(
                        character,
                        threads(),
                        { loading, _ ->
                            if (runs.get() != run) return@previewPiece
                            playback.previewStatus(if (loading) "正在加载模型" else "正在生成试听")
                        },
                        { handle.isCancelled() },
                    )
                } catch (error: Exception) {
                    if (runs.get() != run) return@runAsync
                    if (error !is SynthesisCancelled) {
                        val detail = error.message?.trim().orEmpty().ifBlank { "失败" }
                        log(LogLevel.ERROR, "试听失败 ${character.id} $detail")
                        playback.failPreview(uiText(error))
                    } else {
                        playback.endPreview()
                    }
                    return@runAsync
                }
                val samples = clip.samples
                if (samples == null) {
                    if (runs.get() != run) return@runAsync
                    val detail = clip.error ?: "这句没有读出来"
                    log(LogLevel.WARN, "试听没有声音 ${character.id} $detail")
                    playback.failPreview(detail)
                    return@runAsync
                }
                if (runs.get() != run) return@runAsync
                release = false
                lane.release()
                playback.playPreview(character.id, samples, clip.sampleRate, isQuiet(samples))
            } finally {
                marker.disarm()
                if (release && runs.get() == run) lane.release()
                if (cancel.get() === handle) cancel.set(null)
            }
        }
    }

    private fun logHome(id: String, made: List<Clip>) {
        val playable = made.count { it.samples != null }
        val quiet = made.count { clip -> clip.samples?.let(::isQuiet) == true }
        val broken = made.count { it.error != null }
        val level = if (playable == 0 || broken > 0 || (playable > 0 && quiet == playable)) {
            LogLevel.WARN
        } else {
            LogLevel.INFO
        }
        log(level, "主屏完成 $id ${playable}/${made.size}句可播放")
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "genie-speak").apply { isDaemon = true }
        }
    }
}
