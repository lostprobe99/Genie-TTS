package com.genie.tts.speech

import com.genie.tts.core.StateStore
import com.genie.tts.core.uiText
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

internal enum class PlaybackKind { None, Sentences, Preview }

/** 播放这一刻的状态。句子播放写在 [note]，试听写在 [banner]。 */
internal data class PlaybackState(
    val playing: Boolean = false,
    val kind: PlaybackKind = PlaybackKind.None,
    val previewId: String? = null,
    val active: Int = -1,
    val note: String = "",
    val focusGranted: Boolean = true,
    val banner: String? = null,
    val bannerOwned: Boolean = false,
    val alert: String? = null,
    val alertId: Long = 0,
)

/**
 * 句子播放和试听播放。合成队列不在这里。
 * 停掉上一轮之后，那一轮不再改状态。
 */
internal class PlaybackActions(
    private val sink: (
        samples: FloatArray,
        sampleRate: Int,
        stop: () -> Boolean,
        onStarted: (Boolean) -> Unit,
        onProgress: (Int, Int) -> Unit,
    ) -> Unit,
    private val player: AudioPlayer = AudioPlayer(),
    private val store: StateStore<PlaybackState> = StateStore(PlaybackState()),
) {
    private val generation = AtomicInteger(0)

    fun current(): PlaybackState = store.current()

    fun listen(listener: (PlaybackState) -> Unit): () -> Unit = store.listen(listener)

    fun close() {
        generation.incrementAndGet()
        player.stop()
    }

    /** 没在放就不改状态行，也不动横幅。正在放就停掉并清掉这一轮的字。 */
    fun quietStop() {
        val now = store.current()
        val hadAudio = now.playing || now.previewId != null || now.active >= 0
        generation.incrementAndGet()
        player.stop()
        if (!hadAudio) return
        store.update {
            it.copy(
                playing = false,
                kind = PlaybackKind.None,
                previewId = null,
                active = -1,
                note = "",
                banner = null,
                bannerOwned = true,
            )
        }
    }

    fun stop() {
        val wasPlaying = store.current().playing
        quietStop()
        if (wasPlaying) showStopped()
    }

    fun showStopped() {
        store.update { it.copy(note = "已停止", playing = false, banner = null, bannerOwned = true) }
    }

    fun hideBanner() {
        store.update { it.copy(banner = null, bannerOwned = true) }
    }

    fun beginPreview(id: String) {
        quietStop()
        store.update {
            it.copy(
                playing = false,
                kind = PlaybackKind.Preview,
                previewId = id,
                active = -1,
                note = "",
                banner = "正在加载模型",
                bannerOwned = true,
            )
        }
    }

    fun previewStatus(text: String) {
        store.update { it.copy(banner = text, bannerOwned = true) }
    }

    /** 试听被取消时收起横幅，不弹提示。 */
    fun endPreview() {
        generation.incrementAndGet()
        player.stop()
        store.update {
            it.copy(
                playing = false,
                kind = PlaybackKind.None,
                previewId = null,
                active = -1,
                note = "",
                banner = null,
                bannerOwned = true,
            )
        }
    }

    fun failPreview(message: String) {
        generation.incrementAndGet()
        player.stop()
        store.update {
            it.copy(
                playing = false,
                kind = PlaybackKind.None,
                previewId = null,
                active = -1,
                note = "",
                banner = null,
                bannerOwned = true,
                alert = message,
                alertId = it.alertId + 1,
            )
        }
    }

    fun play(indexes: List<Int>, clips: List<Clip?>) {
        if (indexes.isEmpty()) return
        quietStop()
        val ticket = generation.incrementAndGet()
        store.update {
            it.copy(
                playing = true,
                kind = PlaybackKind.Sentences,
                previewId = null,
                active = -1,
                note = "正在打开播放",
                focusGranted = true,
                banner = null,
                bannerOwned = true,
            )
        }
        player.start(
            block = { stop ->
                val cancelled = { stop() || ticket != generation.get() }
                for (index in indexes) {
                    if (cancelled()) return@start
                    val clip = clips.getOrNull(index) ?: continue
                    val samples = clip.samples ?: continue
                    val quiet = isQuiet(samples)
                    if (ticket != generation.get()) return@start
                    store.update { state ->
                        if (ticket != generation.get()) state else state.copy(active = index)
                    }
                    sink(
                        samples,
                        clip.sampleRate,
                        cancelled,
                        { granted ->
                            if (cancelled()) return@sink
                            store.update { state ->
                                if (ticket != generation.get()) state
                                else state.copy(
                                    focusGranted = granted,
                                    note = playLine(index, 0, samples.size, clip.sampleRate, quiet, granted),
                                )
                            }
                        },
                        { played, total ->
                            if (cancelled()) return@sink
                            store.update { state ->
                                if (ticket != generation.get()) state
                                else state.copy(
                                    note = playLine(index, played, total, clip.sampleRate, quiet, state.focusGranted),
                                )
                            }
                        },
                    )
                }
            },
            onComplete = {
                if (ticket != generation.get()) return@start
                store.update {
                    it.copy(
                        playing = false,
                        kind = PlaybackKind.None,
                        active = -1,
                        note = "播放结束",
                        banner = null,
                        bannerOwned = true,
                    )
                }
            },
            onError = { error ->
                if (ticket != generation.get()) return@start
                store.update {
                    it.copy(
                        playing = false,
                        kind = PlaybackKind.None,
                        active = -1,
                        note = uiText(error),
                        banner = null,
                        bannerOwned = true,
                    )
                }
            },
        )
    }

    fun playPreview(id: String, samples: FloatArray, sampleRate: Int, quiet: Boolean) {
        val ticket = generation.incrementAndGet()
        store.update {
            it.copy(
                playing = true,
                kind = PlaybackKind.Preview,
                previewId = id,
                active = -1,
                note = "",
                focusGranted = true,
                bannerOwned = true,
                banner = if (quiet) "这句几乎没有声音" else "正在打开试听",
            )
        }
        player.start(
            block = { stop ->
                val cancelled = { stop() || ticket != generation.get() }
                sink(
                    samples,
                    sampleRate,
                    cancelled,
                    { granted ->
                        if (cancelled()) return@sink
                        store.update { state ->
                            if (ticket != generation.get()) state
                            else state.copy(
                                focusGranted = granted,
                                banner = previewLine(0, samples.size, sampleRate, quiet, granted),
                            )
                        }
                    },
                    { played, total ->
                        if (cancelled()) return@sink
                        store.update { state ->
                            if (ticket != generation.get()) state
                            else state.copy(
                                banner = previewLine(played, total, sampleRate, quiet, state.focusGranted),
                            )
                        }
                    },
                )
            },
            onComplete = {
                if (ticket != generation.get()) return@start
                store.update {
                    it.copy(
                        playing = false,
                        kind = PlaybackKind.None,
                        previewId = null,
                        active = -1,
                        banner = null,
                        bannerOwned = true,
                    )
                }
            },
            onError = { error ->
                if (ticket != generation.get()) return@start
                store.update {
                    it.copy(
                        playing = false,
                        kind = PlaybackKind.None,
                        previewId = null,
                        active = -1,
                        banner = null,
                        bannerOwned = true,
                        alert = uiText(error),
                        alertId = it.alertId + 1,
                    )
                }
            },
        )
    }

    private fun playLine(
        index: Int,
        played: Int,
        total: Int,
        sampleRate: Int,
        quiet: Boolean,
        granted: Boolean,
    ): String {
        val clock = "${formatCount(played, sampleRate)}/${formatCount(total, sampleRate)} 秒"
        val head = "正在播放第 ${index + 1} 句 · $clock"
        return when {
            quiet -> "$head，这句几乎没有声音"
            !granted -> "声音可能被其他应用占用。$head"
            else -> head
        }
    }

    private fun previewLine(played: Int, total: Int, sampleRate: Int, quiet: Boolean, granted: Boolean): String {
        val clock = "${formatCount(played, sampleRate)}/${formatCount(total, sampleRate)} 秒"
        return when {
            quiet -> "正在试听 $clock，这句几乎没有声音"
            !granted -> "声音可能被其他应用占用。正在试听 $clock"
            else -> "正在试听 $clock"
        }
    }

    private fun formatCount(samples: Int, sampleRate: Int): String {
        val seconds = if (sampleRate <= 0) 0.0 else samples.toDouble() / sampleRate
        return "%.1f".format(Locale.US, seconds)
    }
}

internal fun invalidateSpokenAudio(playback: PlaybackActions, synthesis: SynthesisActions) {
    val hadClips = synthesis.current().clips.isNotEmpty()
    val wasPlaying = playback.current().playing
    playback.quietStop()
    if (!hadClips) {
        if (wasPlaying) playback.showStopped()
        return
    }
    synthesis.clear()
}
