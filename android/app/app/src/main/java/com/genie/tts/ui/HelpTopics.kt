package com.genie.tts.ui

import androidx.core.content.ContextCompat
import com.genie.tts.R
import com.genie.tts.service.TtsPorts
import com.genie.tts.speech.Prosody
import com.genie.tts.speech.Release
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

internal sealed class HelpPiece {
    data class Text(val value: String) : HelpPiece()
    data class Steps(val lines: List<Pair<String, String>>) : HelpPiece()
    data class Bullets(val lines: List<String>) : HelpPiece()
    data class Files(val names: List<String>) : HelpPiece()
    data class Tips(val lines: List<Pair<String, String>>) : HelpPiece()
    data object Copy : HelpPiece()
}

internal data class HelpQuestion(
    val title: String,
    val pieces: List<HelpPiece>,
    val startOpen: Boolean,
)

internal data class HelpTopic(
    val title: String,
    val badge: String?,
    val badgeInk: Int,
    val badgeFill: Int,
    val dot: Int,
    val tags: Set<String>,
    val questions: List<HelpQuestion>,
)

internal fun localTts(port: Int): String = "http://127.0.0.1:$port/tts"

internal fun helpCopyText(context: Context, port: Int): String {
    return context.getString(R.string.help_copy_body, localTts(port))
}

internal fun helpTopics(context: Context, port: Int): List<HelpTopic> {
    fun tone(id: Int) = ContextCompat.getColor(context, id)
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    val endpoint = localTts(port)
    val bytes = "%,d".format(Locale.US, Prosody.MODEL_BYTES)
    return listOf(
        HelpTopic(
            title = text(R.string.help_topic_legado),
            badge = text(R.string.help_badge_often),
            badgeInk = tone(R.color.teal),
            badgeFill = tone(R.color.mint),
            dot = tone(R.color.teal_live),
            tags = setOf("legado", "starter"),
            questions = listOf(
                HelpQuestion(
                    title = text(R.string.help_q_legado),
                    startOpen = true,
                    pieces = listOf(
                        HelpPiece.Text(text(R.string.help_legado_intro)),
                        HelpPiece.Steps(
                            listOf(
                                text(R.string.help_legado_step_start) to text(R.string.help_legado_step_start_body),
                                text(R.string.help_legado_step_check) to text(
                                    R.string.help_legado_step_check_body,
                                    endpoint,
                                    TtsPorts.DEFAULT,
                                ),
                                text(R.string.help_legado_step_fill) to text(R.string.help_legado_step_fill_body),
                            ),
                        ),
                        HelpPiece.Copy,
                    ),
                ),
                HelpQuestion(
                    title = text(R.string.help_q_lan),
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text(text(R.string.help_lan_intro)),
                        HelpPiece.Bullets(
                            listOf(
                                text(R.string.help_lan_local, endpoint),
                                text(R.string.help_lan_example, port),
                                text(R.string.help_lan_port),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        HelpTopic(
            title = text(R.string.help_topic_battery),
            badge = text(R.string.help_badge_core),
            badgeInk = tone(R.color.green_ink),
            badgeFill = tone(R.color.green_wash),
            dot = tone(R.color.green_mid),
            tags = setOf("battery", "starter"),
            questions = listOf(
                HelpQuestion(
                    title = text(R.string.help_q_screen),
                    startOpen = true,
                    pieces = listOf(
                        HelpPiece.Text(text(R.string.help_screen_intro)),
                        HelpPiece.Tips(
                            listOf(
                                text(R.string.help_screen_battery) to text(R.string.help_screen_battery_body),
                                text(R.string.help_screen_lock) to text(R.string.help_screen_lock_body),
                                text(R.string.help_screen_notice) to text(R.string.help_screen_notice_body),
                            ),
                        ),
                    ),
                ),
                HelpQuestion(
                    title = text(R.string.help_q_notice),
                    startOpen = false,
                    pieces = listOf(HelpPiece.Text(text(R.string.help_notice_body))),
                ),
            ),
        ),
        HelpTopic(
            title = text(R.string.help_topic_model),
            badge = null,
            badgeInk = 0,
            badgeFill = 0,
            dot = tone(R.color.cyan),
            tags = setOf("model", "starter"),
            questions = listOf(
                HelpQuestion(
                    title = text(R.string.help_q_broken),
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text(text(R.string.help_broken_intro)),
                        HelpPiece.Files(
                            listOf(
                                "t2s_encoder_fp32.onnx",
                                "t2s_first_stage_decoder_fp32.onnx",
                                "t2s_stage_decoder_fp32.onnx",
                                "vits_fp32.onnx",
                            ),
                        ),
                        HelpPiece.Text(text(R.string.help_broken_tail)),
                    ),
                ),
                HelpQuestion(
                    title = text(R.string.help_q_rename),
                    startOpen = false,
                    pieces = listOf(HelpPiece.Text(text(R.string.help_rename_body))),
                ),
            ),
        ),
        HelpTopic(
            title = text(R.string.help_topic_port),
            badge = null,
            badgeInk = 0,
            badgeFill = 0,
            dot = tone(R.color.teal_deep),
            tags = setOf("service", "model"),
            questions = listOf(
                HelpQuestion(
                    title = text(R.string.help_q_prosody),
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text(text(R.string.help_prosody_body, Release.PROSODY_URL, bytes)),
                    ),
                ),
                HelpQuestion(
                    title = text(R.string.help_q_port, TtsPorts.DEFAULT),
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text(text(R.string.help_port_body, TtsPorts.DEFAULT, endpoint)),
                    ),
                ),
            ),
        ),
    )
}

internal fun fillHelpList(
    list: RecyclerView,
    port: Int,
    query: String,
    category: String,
    open: MutableMap<String, Boolean>,
    onToggle: (String) -> Unit,
    onCopy: () -> Unit,
): Boolean {
    val needle = query.trim()
    val entries = ArrayList<HelpEntry>()
    helpTopics(list.context, port).forEach { topic ->
        if (category != "all" && category !in topic.tags) return@forEach
        val questions = topic.questions.filter { question ->
            needle.isEmpty() || question.plain(port).contains(needle, ignoreCase = true)
        }
        if (questions.isEmpty()) return@forEach
        entries.add(HelpEntry.Head(topic))
        questions.forEach { question ->
            val expanded = open.getOrPut(question.title) { question.startOpen }
            entries.add(HelpEntry.Card(topic, question, expanded))
        }
    }
    val adapter = (list.adapter as? HelpListAdapter) ?: HelpListAdapter().also {
        list.layoutManager = LinearLayoutManager(list.context)
        list.adapter = it
        list.itemAnimator = null
        list.isNestedScrollingEnabled = false
    }
    adapter.submit(entries, port, onToggle, onCopy)
    return entries.any { it is HelpEntry.Card }
}

private sealed class HelpEntry {
    data class Head(val topic: HelpTopic) : HelpEntry()
    data class Card(val topic: HelpTopic, val question: HelpQuestion, val expanded: Boolean) : HelpEntry()
}

private class HelpListAdapter : RecyclerView.Adapter<HelpListAdapter.Holder>() {
    private var entries = emptyList<HelpEntry>()
    private var port = 0
    private var onToggle: (String) -> Unit = {}
    private var onCopy: () -> Unit = {}

    fun submit(next: List<HelpEntry>, port: Int, onToggle: (String) -> Unit, onCopy: () -> Unit) {
        val old = entries
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int): Boolean = when {
                old[a] is HelpEntry.Head && next[b] is HelpEntry.Head ->
                    (old[a] as HelpEntry.Head).topic.title == (next[b] as HelpEntry.Head).topic.title
                old[a] is HelpEntry.Card && next[b] is HelpEntry.Card ->
                    (old[a] as HelpEntry.Card).question.title == (next[b] as HelpEntry.Card).question.title
                else -> false
            }
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
        })
        entries = next
        this.port = port
        this.onToggle = onToggle
        this.onCopy = onCopy
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return Holder(root)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.root.removeAllViews()
        when (val entry = entries[position]) {
            is HelpEntry.Head -> holder.root.addView(topicHead(holder.root, entry.topic))
            is HelpEntry.Card -> holder.root.addView(
                questionCard(holder.root, entry.topic, entry.question, entry.expanded, port, onToggle, onCopy),
            )
        }
    }

    class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)
}

private fun HelpQuestion.plain(port: Int): String {
    return buildString {
        append(title)
        pieces.forEach { piece ->
            when (piece) {
                is HelpPiece.Text -> append(piece.value)
                is HelpPiece.Steps -> piece.lines.forEach { append(it.first).append(it.second) }
                is HelpPiece.Bullets -> piece.lines.forEach { append(it) }
                is HelpPiece.Files -> piece.names.forEach { append(it) }
                is HelpPiece.Tips -> piece.lines.forEach { append(it.first).append(it.second) }
                HelpPiece.Copy -> append(localTts(port))
            }
        }
    }
}

private fun topicHead(list: LinearLayout, topic: HelpTopic): View {
    val row = LinearLayout(list.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(list, 4), dp(list, 8), dp(list, 4), dp(list, 6))
    }
    row.addView(TextView(list.context).apply {
        text = topic.title
        textSize = 12f
        setTextColor(tone(list, R.color.slate_strong))
        setTypeface(typeface, Typeface.BOLD)
    })
    if (topic.badge != null) {
        row.addView(
            TextView(list.context).apply {
                text = topic.badge
                textSize = 10f
                setTextColor(topic.badgeInk)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(list, 6), dp(list, 2), dp(list, 6), dp(list, 2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(list, 4).toFloat()
                    setColor(topic.badgeFill)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(list, 6) },
        )
    }
    return row
}

private fun questionCard(
    list: LinearLayout,
    topic: HelpTopic,
    question: HelpQuestion,
    expanded: Boolean,
    port: Int,
    onToggle: (String) -> Unit,
    onCopy: () -> Unit,
): View {
    val card = LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        background = list.context.getDrawable(R.drawable.bg_prosody_card)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(list, 8) }
    }
    val head = LinearLayout(list.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(list, 12), dp(list, 12), dp(list, 12), dp(list, 12))
        setOnClickListener { onToggle(question.title) }
    }
    head.addView(View(list.context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (expanded) topic.dot else tone(list, R.color.slate))
        }
        layoutParams = LinearLayout.LayoutParams(dp(list, 6), dp(list, 6))
    })
    head.addView(
        TextView(list.context).apply {
            text = question.title
            textSize = 13f
            setTextColor(tone(list, R.color.text_main))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(list, 8)
            marginEnd = dp(list, 8)
        },
    )
    head.addView(ImageView(list.context).apply {
        setImageResource(R.drawable.ic_chevron_down)
        imageTintList = android.content.res.ColorStateList.valueOf(tone(list, R.color.slate))
        rotation = if (expanded) 180f else 0f
        layoutParams = LinearLayout.LayoutParams(dp(list, 16), dp(list, 16))
    })
    card.addView(head)
    if (!expanded) return card
    val body = LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(list, 12), 0, dp(list, 12), dp(list, 12))
    }
    question.pieces.forEach { piece ->
        when (piece) {
            is HelpPiece.Text -> body.addView(bodyText(list, piece.value))
            is HelpPiece.Steps -> body.addView(stepsBlock(list, piece.lines))
            is HelpPiece.Bullets -> body.addView(bulletsBlock(list, piece.lines))
            is HelpPiece.Files -> body.addView(filesBlock(list, piece.names))
            is HelpPiece.Tips -> body.addView(tipsBlock(list, piece.lines))
            HelpPiece.Copy -> body.addView(copyBlock(list, port, onCopy))
        }
    }
    card.addView(body)
    return card
}

private fun bodyText(list: LinearLayout, value: String): TextView {
    return TextView(list.context).apply {
        text = value
        textSize = 12f
        setTextColor(tone(list, R.color.slate_text))
        setLineSpacing(dp(list, 2).toFloat(), 1f)
        setPadding(0, dp(list, 2), 0, dp(list, 6))
    }
}

private fun stepsBlock(list: LinearLayout, lines: List<Pair<String, String>>): View {
    val box = stack(list, tone(list, R.color.mist_card))
    lines.forEachIndexed { index, (label, detail) ->
        val row = LinearLayout(list.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(list, 4), 0, dp(list, 4))
        }
        row.addView(TextView(list.context).apply {
            text = "${index + 1}"
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tone(list, R.color.teal_live))
            }
            layoutParams = LinearLayout.LayoutParams(dp(list, 16), dp(list, 16)).apply {
                topMargin = dp(list, 1)
            }
        })
        val span = SpannableString("$label$detail")
        span.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, 0)
        span.setSpan(ForegroundColorSpan(tone(list, R.color.text_main)), 0, label.length, 0)
        row.addView(
            TextView(list.context).apply {
                text = span
                textSize = 12f
                setTextColor(tone(list, R.color.slate_text))
                setLineSpacing(dp(list, 2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(list, 8)
            },
        )
        box.addView(row)
    }
    return box
}

private fun bulletsBlock(list: LinearLayout, lines: List<String>): View {
    val box = LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(list, 4))
    }
    lines.forEach { line ->
        box.addView(TextView(list.context).apply {
            text = "• $line"
            textSize = 12f
            setTextColor(tone(list, R.color.slate_text))
            setLineSpacing(dp(list, 2).toFloat(), 1f)
            setPadding(dp(list, 4), dp(list, 2), 0, dp(list, 2))
        })
    }
    return box
}

private fun filesBlock(list: LinearLayout, names: List<String>): View {
    val box = stack(list, tone(list, R.color.mist_card))
    names.forEach { name ->
        box.addView(TextView(list.context).apply {
            text = name
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(tone(list, R.color.teal))
            setPadding(0, dp(list, 2), 0, dp(list, 2))
        })
    }
    return box
}

private fun tipsBlock(list: LinearLayout, lines: List<Pair<String, String>>): View {
    val box = LinearLayout(list.context).apply { orientation = LinearLayout.VERTICAL }
    lines.forEach { (title, detail) ->
        val card = stack(list, tone(list, R.color.mist_card))
        card.addView(TextView(list.context).apply {
            text = title
            textSize = 12f
            setTextColor(tone(list, R.color.text_main))
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(list.context).apply {
            text = detail
            textSize = 11f
            setTextColor(tone(list, R.color.text_sub))
            setPadding(0, dp(list, 2), 0, 0)
        })
        box.addView(card)
    }
    return box
}

private fun copyBlock(list: LinearLayout, port: Int, onCopy: () -> Unit): View {
    val row = LinearLayout(list.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(list, 10), dp(list, 8), dp(list, 8), dp(list, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(list, 12).toFloat()
            setColor(tone(list, R.color.mint))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(list, 4) }
    }
    row.addView(
        TextView(list.context).apply {
            text = localTts(port)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(tone(list, R.color.teal_deep))
            maxLines = 2
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    row.addView(TextView(list.context).apply {
        text = list.context.getString(R.string.help_copy_config)
        textSize = 11f
        setTextColor(android.graphics.Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(list, 10), dp(list, 6), dp(list, 10), dp(list, 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(list, 8).toFloat()
            setColor(tone(list, R.color.teal_live))
        }
        setOnClickListener { onCopy() }
    })
    return row
}

private fun stack(list: LinearLayout, fill: Int): LinearLayout {
    return LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(list, 10), dp(list, 8), dp(list, 10), dp(list, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(list, 12).toFloat()
            setColor(fill)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(list, 6) }
    }
}

private fun dp(list: View, value: Int): Int {
    if (value == 0) return 0
    val id = list.resources.getIdentifier("dp_$value", "dimen", list.context.packageName)
    return list.resources.getDimensionPixelSize(id)
}

private fun tone(view: View, id: Int) = ContextCompat.getColor(view.context, id)
