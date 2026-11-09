package com.genie.tts.ui

import com.genie.tts.R
import com.genie.tts.speech.Clip
import com.genie.tts.speech.secondsOf
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
internal class SpeakController(private val activity: AppCompatActivity) {
    val sentence: EditText = activity.findViewById(R.id.sentence)
    val speed: SeekBar = activity.findViewById(R.id.speed)
    private val sentenceCard: View = activity.findViewById(R.id.sentenceCard)
    private val sentenceScroll: View = activity.findViewById(R.id.sentenceScroll)
    private val sentenceList: LinearLayout = activity.findViewById(R.id.sentenceList)
    private val sentenceHeading: TextView = activity.findViewById(R.id.sentenceHeading)
    private val sentenceBadge: TextView = activity.findViewById(R.id.sentenceBadge)
    private val workDot: View = activity.findViewById(R.id.workDot)
    private val workMetrics: TextView = activity.findViewById(R.id.workMetrics)
    private val workNoteLine: TextView = activity.findViewById(R.id.workNote)
    private val speedLabel: TextView = activity.findViewById(R.id.speedLabel)
    private val synthesizeButton: Button = activity.findViewById(R.id.synthesize)
    private val playButton: Button = activity.findViewById(R.id.play)
    private val workStatus: TextView = activity.findViewById(R.id.workStatus)

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
        val rowHeight = dp(40)
        val scrollHeight = rowHeight * 3
        if (sentenceScroll.layoutParams.height != scrollHeight) {
            sentenceScroll.layoutParams.height = scrollHeight
            sentenceScroll.requestLayout()
        }
        sentenceList.removeAllViews()
        state.clips.forEachIndexed { index, clip ->
            val hot = index == state.active && (state.playing || state.synthesizing)
            val samples = clip?.samples
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                if (hot) background = rounded(color(R.color.mint), 6f)
            }
            row.addView(TextView(activity).apply {
                text = "%02d".format(Locale.US, index + 1)
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(if (hot) Color.WHITE else color(R.color.text_sub))
                background = rounded(if (hot) color(R.color.primary) else color(R.color.mist), 4f)
                minWidth = dp(20)
                minHeight = dp(20)
            })
            row.addView(TextView(activity).apply {
                text = clip?.text.orEmpty()
                textSize = 12f
                typeface = Typeface.SERIF
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(if (hot) R.color.text_main else R.color.text_sub))
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val detail = when {
                samples != null -> "${formatSec(secondsOf(samples, clip.sampleRate))}s"
                !clip?.error.isNullOrBlank() -> clip?.error
                state.synthesizing && index == state.active -> activity.getString(R.string.sentence_working)
                else -> ""
            }
            row.addView(TextView(activity).apply {
                text = detail
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(
                    when {
                        !clip?.error.isNullOrBlank() -> color(R.color.amber)
                        hot -> color(R.color.primary)
                        else -> color(R.color.text_sub)
                    },
                )
            })
            if (samples != null) {
                val button = ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_role_play)
                    imageTintList = ColorStateList.valueOf(if (hot) Color.WHITE else color(R.color.text_sub))
                    background = rounded(if (hot) color(R.color.primary) else color(R.color.mist), 99f)
                    contentDescription = activity.getString(R.string.play_sentence)
                    scaleType = ImageView.ScaleType.CENTER
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    val side = dp(24)
                    layoutParams = LinearLayout.LayoutParams(side, side).apply { marginStart = dp(8) }
                }
                if (!state.busy) {
                    button.setOnClickListener { onPlay(index) }
                    row.setOnClickListener { onPlay(index) }
                }
                row.addView(button)
            }
            sentenceList.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight),
            )
        }
        paint(state)
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
        val child = sentenceList.getChildAt(active) ?: return
        sentenceScroll.post { (sentenceScroll as android.widget.ScrollView).smoothScrollTo(0, child.top) }
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

    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * activity.resources.displayMetrics.density
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}
