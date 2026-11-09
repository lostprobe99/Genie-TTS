package com.genie.tts.ui

import com.genie.tts.databinding.ActivityMainBinding
import com.genie.tts.R
import com.genie.tts.databinding.ItemSentenceBinding
import com.genie.tts.speech.Clip
import com.genie.tts.speech.secondsOf
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** 主屏这一刻要画出来的内容。控制器不自己去读合成器或日志。 */
internal data class SpeakSnapshot(
    val clips: List<Clip?>,
    val synthesizing: Boolean,
    val loadingModel: Boolean,
    val playing: Boolean,
    val busy: Boolean,
    val active: Int,
    val workNote: String,
    val elapsedMs: Long,
    val canSpeak: Boolean,
)

/**
 * 主屏的分句列表、语速、合成和播放按钮、以及底下的状态。
 * 点下去之后做什么，由外面传进来的回调决定。
 */
internal class SpeakController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {
    val sentence: EditText = binding.speakPage.sentence
    val speed: SeekBar = binding.speakPage.speed
    private val sentenceCard: View = binding.speakPage.sentenceCard
    private val sentenceList: RecyclerView = binding.speakPage.sentenceList
    private val sentences = SentenceAdapter(activity)
    private val sentenceHeading: TextView = binding.speakPage.sentenceHeading
    private val sentenceBadge: TextView = binding.speakPage.sentenceBadge
    private val workDot: View = binding.speakPage.workDot
    private val workMetrics: TextView = binding.speakPage.workMetrics
    private val workNoteLine: TextView = binding.speakPage.workNote
    private val speedLabel: TextView = binding.speakPage.speedLabel
    private val synthesizeButton: Button = binding.speakPage.synthesize
    private val playButton: Button = binding.speakPage.play
    private val workStatus: TextView = binding.speakPage.workStatus

    private var drawn = SpeakSnapshot(
        clips = emptyList(),
        synthesizing = false,
        loadingModel = false,
        playing = false,
        busy = false,
        active = -1,
        workNote = "",
        elapsedMs = 0L,
        canSpeak = false,
    )

    fun bind(onSynthesize: () -> Unit, onTogglePlay: () -> Unit) {
        sentenceList.layoutManager = LinearLayoutManager(activity)
        sentenceList.adapter = sentences
        sentenceList.itemAnimator = null
        synthesizeButton.setOnClickListener { onSynthesize() }
        playButton.setOnClickListener { onTogglePlay() }
    }

    /** 分句有变化就重画列表，否则只改进度。返回值表示按钮要不要跟着改。 */
    fun apply(state: SpeakSnapshot, onPlay: (Int) -> Unit): Boolean {
        val listChanged = state.clips != drawn.clips ||
            state.synthesizing != drawn.synthesizing ||
            state.loadingModel != drawn.loadingModel ||
            state.active != drawn.active ||
            state.busy != drawn.busy ||
            state.playing != drawn.playing
        if (listChanged) renderSentences(state, onPlay) else paint(state)
        val controlsChanged = listChanged ||
            state.workNote != drawn.workNote ||
            state.canSpeak != drawn.canSpeak ||
            state.elapsedMs != drawn.elapsedMs
        if (controlsChanged) updateControls(state)
        if (state.active != drawn.active && state.active >= 0) scrollToActive(state.active)
        drawn = state
        return controlsChanged
    }

    fun renderSpeed(rate: String) {
        speedLabel.text = labeled(activity.getString(R.string.speed_adjust), rate, "  ")
    }

    fun renderSentences(state: SpeakSnapshot, onPlay: (Int) -> Unit) {
        sentenceCard.visibility = View.VISIBLE
        sentenceHeading.text = activity.getString(R.string.sentence_heading, state.clips.size)
        sentenceBadge.text = when {
            state.synthesizing && state.loadingModel -> activity.getString(R.string.loading_model)
            state.synthesizing -> activity.getString(R.string.synthesizing_now)
            state.playing -> activity.getString(R.string.playing_now)
            state.clips.any { it?.samples != null } -> activity.getString(R.string.synth_done_hint)
            else -> ""
        }
        sentenceBadge.visibility = if (sentenceBadge.text.isNullOrBlank()) View.GONE else View.VISIBLE
        sentences.submit(sentenceRows(state), onPlay)
        paint(state)
    }

    private fun sentenceRows(state: SpeakSnapshot): List<SentenceRow> {
        return state.clips.mapIndexed { index, clip ->
            val hot = index == state.active && (state.playing || state.synthesizing)
            val samples = clip?.samples
            val detail = when {
                samples != null -> "${formatSec(secondsOf(samples, clip.sampleRate))}s"
                !clip?.error.isNullOrBlank() -> clip?.error.orEmpty()
                state.synthesizing && index == state.active -> activity.getString(R.string.sentence_working)
                else -> ""
            }
            val tone = when {
                !clip?.error.isNullOrBlank() -> R.color.amber
                hot -> R.color.primary
                else -> R.color.text_sub
            }
            SentenceRow(
                index = index,
                text = clip?.text.orEmpty(),
                hot = hot,
                detail = detail,
                tone = tone,
                playable = samples != null,
                enabled = samples != null && !state.busy,
            )
        }
    }

    fun updateControls(state: SpeakSnapshot) {
        sentence.isEnabled = !state.busy
        speed.isEnabled = !state.busy
        when {
            state.synthesizing && state.loadingModel -> {
                synthesizeButton.isEnabled = true
                synthesizeButton.text = activity.getString(R.string.loading_short)
            }
            state.synthesizing -> {
                synthesizeButton.isEnabled = true
                val shown = (state.active + 1).coerceAtLeast(1)
                synthesizeButton.text = "$shown/${state.clips.size.coerceAtLeast(1)}"
            }
            else -> {
                synthesizeButton.isEnabled = state.canSpeak
                synthesizeButton.setText(R.string.synthesize)
            }
        }
        synthesizeButton.backgroundTintList = null
        synthesizeButton.setTextColor(color(android.R.color.white))
        synthesizeButton.alpha = if (synthesizeButton.isEnabled) 1f else 0.4f
        val ready = state.clips.any { it?.samples != null }
        when {
            state.playing -> {
                playButton.isEnabled = true
                playButton.setText(R.string.stop)
            }
            ready && !state.busy && !state.synthesizing -> {
                playButton.isEnabled = true
                playButton.setText(R.string.play)
            }
            else -> {
                playButton.isEnabled = false
                playButton.setText(R.string.play)
            }
        }
        playButton.backgroundTintList = null
        playButton.setTextColor(color(R.color.primary))
        playButton.alpha = if (playButton.isEnabled) 1f else 0.4f
    }

    fun paint(state: SpeakSnapshot) {
        val warn = state.workNote.contains("没有声音") ||
            state.workNote.contains("占用") ||
            state.workNote.contains("没有可以播放")
        workStatus.text = when {
            state.synthesizing && state.loadingModel -> activity.getString(R.string.loading_short)
            state.synthesizing -> activity.getString(R.string.synth_short)
            state.playing -> activity.getString(R.string.play_short)
            state.clips.any { it?.samples != null } -> activity.getString(R.string.done)
            state.clips.isNotEmpty() -> activity.getString(R.string.done)
            else -> activity.getString(R.string.status_idle)
        }
        val metrics = metricsLine(state)
        val playingLine = state.workNote.startsWith("正在播放")
        workMetrics.text = when {
            playingLine -> state.workNote
            metrics.isNotBlank() -> metrics
            else -> if (isProgressNote(state.workNote)) "" else state.workNote
        }
        val extra = state.workNote.isNotBlank() && !isProgressNote(state.workNote) && workMetrics.text != state.workNote
        workNoteLine.text = state.workNote
        workNoteLine.visibility = if (extra) View.VISIBLE else View.GONE
        workDot.backgroundTintList = ColorStateList.valueOf(
            when {
                warn -> color(R.color.amber)
                state.playing || state.synthesizing -> color(R.color.primary)
                state.clips.any { it?.samples != null } -> color(R.color.green)
                else -> color(R.color.mist_line)
            },
        )
    }

    fun scrollToActive(active: Int) {
        if (active < 0) return
        sentenceList.post { sentenceList.smoothScrollToPosition(active) }
    }

    private fun metricsLine(state: SpeakSnapshot): String {
        if (state.clips.isEmpty()) return activity.getString(R.string.metrics_empty)
        val total = state.clips.size
        val finished = state.clips.count { it?.samples != null || !it?.error.isNullOrBlank() }
        val shown = if (state.synthesizing) (state.active + 1).coerceIn(0, total) else finished
        val parts = mutableListOf(activity.getString(R.string.metrics_pieces, shown, total))
        if (state.elapsedMs > 0) parts += activity.getString(R.string.metrics_elapsed, formatElapsed(state.elapsedMs))
        val audio = state.clips.sumOf { clip ->
            val samples = clip?.samples ?: return@sumOf 0.0
            secondsOf(samples, clip.sampleRate)
        }
        if (audio > 0) {
            parts += activity.getString(R.string.metrics_audio, formatSec(audio))
            if (state.elapsedMs > 0) {
                val rate = "%.2f".format(Locale.US, (state.elapsedMs / 1000.0) / audio)
                parts += activity.getString(R.string.metrics_rtf, rate)
            }
        }
        return parts.joinToString("  ")
    }

    private fun isProgressNote(note: String): Boolean {
        return note.isBlank() ||
            note.startsWith("正在加载") ||
            note.startsWith("正在合成") ||
            note.startsWith("正在打开") ||
            note.startsWith("正在播放") ||
            note.startsWith("合成完成") ||
            note == "播放结束" ||
            note == "已停止"
    }

    private fun labeled(label: String, value: String, gap: String): CharSequence {
        val text = "$label$gap$value"
        val start = text.length - value.length
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(color(R.color.primary)),
                start,
                text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                start,
                text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun formatElapsed(ms: Long): String {
        return if (ms < 1000) "${ms}ms" else "${"%.1f".format(Locale.US, ms / 1000.0)}s"
    }

    private fun formatSec(seconds: Double): String = "%.1f".format(Locale.US, seconds)

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}

private data class SentenceRow(
    val index: Int,
    val text: String,
    val hot: Boolean,
    val detail: String,
    val tone: Int,
    val playable: Boolean,
    val enabled: Boolean,
)

private class SentenceAdapter(
    private val activity: AppCompatActivity,
) : RecyclerView.Adapter<SentenceAdapter.Holder>() {
    private var rows = emptyList<SentenceRow>()
    private var onPlay: (Int) -> Unit = {}

    fun submit(next: List<SentenceRow>, onPlay: (Int) -> Unit) {
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].index == next[b].index
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
        })
        rows = next
        this.onPlay = onPlay
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val binding = ItemSentenceBinding.inflate(activity.layoutInflater, parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position], onPlay)
    }

    class Holder(private val binding: ItemSentenceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SentenceRow, onPlay: (Int) -> Unit) {
            val view = binding.root
            view.background = if (row.hot) rounded(view, R.color.mint, R.dimen.dp_6) else null
            binding.sentenceIndex.text = "%02d".format(Locale.US, row.index + 1)
            binding.sentenceIndex.setTextColor(if (row.hot) Color.WHITE else color(view, R.color.text_sub))
            binding.sentenceIndex.background = rounded(
                view,
                if (row.hot) R.color.primary else R.color.mist,
                R.dimen.dp_4,
            )
            binding.sentenceText.text = row.text
            binding.sentenceText.setTextColor(color(view, if (row.hot) R.color.text_main else R.color.text_sub))
            binding.sentenceDetail.text = row.detail
            binding.sentenceDetail.setTextColor(color(view, row.tone))
            binding.sentencePlay.visibility = if (row.playable) View.VISIBLE else View.GONE
            if (row.playable) {
                binding.sentencePlay.imageTintList = ColorStateList.valueOf(
                    if (row.hot) Color.WHITE else color(view, R.color.text_sub),
                )
                binding.sentencePlay.background = rounded(
                    view,
                    if (row.hot) R.color.primary else R.color.mist,
                    R.dimen.dp_99,
                )
                val play = if (row.enabled) View.OnClickListener { onPlay(row.index) } else null
                binding.sentencePlay.setOnClickListener(play)
                view.setOnClickListener(play)
            } else {
                binding.sentencePlay.setOnClickListener(null)
                view.setOnClickListener(null)
            }
        }

        private fun rounded(view: View, fill: Int, radius: Int): GradientDrawable {
            return GradientDrawable().apply {
                setColor(color(view, fill))
                cornerRadius = view.dimen(radius)
            }
        }

        private fun color(view: View, id: Int) = ContextCompat.getColor(view.context, id)
    }
}
