package com.genie.tts.ui

import androidx.core.content.ContextCompat
import com.genie.tts.R
import com.genie.tts.core.DEFAULT_LOG_LEVELS
import com.genie.tts.core.LogLevel
import com.genie.tts.core.LogLine
import com.genie.tts.core.TtsLog
import com.genie.tts.core.shownLogLines
import com.genie.tts.service.ServiceStates
import com.genie.tts.core.toggleLogLevel
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Debug
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Owns the logs page's filter state, bindings and rendering.
 *
 * File operations stay in [MainActivity]; this class is only responsible for
 * presenting the current [TtsLog] snapshot.
 */
internal class LogPageController(
    private val activity: AppCompatActivity,
    private val isVisible: () -> Boolean,
) {
    private var levels: Set<LogLevel> = DEFAULT_LOG_LEVELS
    private val query: EditText = activity.findViewById(R.id.logQuery)
    private val list: RecyclerView = activity.findViewById(R.id.logList)
    private val empty: TextView = activity.findViewById(R.id.logEmpty)
    private val end: TextView = activity.findViewById(R.id.logEnd)
    private val rows = LogRowAdapter { copyLine(it) }
    val autoScroll: SwitchCompat = activity.findViewById(R.id.logAutoScroll)

    fun bind(onBack: () -> Unit, onExport: () -> Unit, onClear: () -> Unit) {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = rows
        list.itemAnimator = null
        activity.findViewById<View>(R.id.logsBack).setOnClickListener { onBack() }
        activity.findViewById<View>(R.id.logsRefresh).setOnClickListener {
            TtsLog.reload()
            render(true)
        }
        activity.findViewById<View>(R.id.logsHeaderExport).setOnClickListener { onExport() }
        activity.findViewById<View>(R.id.logsMore).setOnClickListener { copyPath() }
        activity.findViewById<View>(R.id.logFilterAll).setOnClickListener {
            levels = enumValues<LogLevel>().toSet()
            render(follow = false, reset = true)
        }
        activity.findViewById<View>(R.id.logFilterInfo).setOnClickListener { toggle(LogLevel.INFO) }
        activity.findViewById<View>(R.id.logFilterWarn).setOnClickListener { toggle(LogLevel.WARN) }
        activity.findViewById<View>(R.id.logFilterError).setOnClickListener { toggle(LogLevel.ERROR) }
        activity.findViewById<View>(R.id.logFilterDebug).setOnClickListener { toggle(LogLevel.DEBUG) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                activity.findViewById<View>(R.id.logQueryClear).visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (isVisible()) render(follow = false, reset = true)
            }
        })
        activity.findViewById<View>(R.id.logQueryClear).setOnClickListener { query.setText("") }
        val switchStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        autoScroll.trackTintList =
            android.content.res.ColorStateList(switchStates, intArrayOf(color(R.color.teal_press), color(R.color.mist_line)))
        autoScroll.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        activity.findViewById<View>(R.id.logClear).setOnClickListener { onClear() }
        activity.findViewById<View>(R.id.logCopy).setOnClickListener { copyAll() }
        activity.findViewById<View>(R.id.logExport).setOnClickListener { onExport() }
    }

    fun render(follow: Boolean, reset: Boolean = false) {
        val entries = TtsLog.entries()
        val shown = shownLogLines(entries, levels, query.text.toString())
        paintChip(R.id.logFilterAll, null, entries.size)
        paintChip(R.id.logFilterInfo, LogLevel.INFO, entries.count { it.level == LogLevel.INFO })
        paintChip(R.id.logFilterWarn, LogLevel.WARN, entries.count { it.level == LogLevel.WARN })
        paintChip(R.id.logFilterError, LogLevel.ERROR, entries.count { it.level == LogLevel.ERROR })
        paintChip(R.id.logFilterDebug, LogLevel.DEBUG, entries.count { it.level == LogLevel.DEBUG })
        paintBanner()
        rows.submit(shown)
        val nothing = shown.isEmpty()
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
        empty.text = activity.getString(if (entries.isEmpty()) R.string.log_empty else R.string.log_none)
        list.visibility = if (nothing) View.INVISIBLE else View.VISIBLE
        end.visibility = if (nothing) View.GONE else View.VISIBLE
        val allLevels = levels.size == enumValues<LogLevel>().size
        end.text = activity.getString(
            if (allLevels && query.text.isNullOrBlank()) R.string.log_shown_all else R.string.log_shown_filtered,
        )
        activity.findViewById<TextView>(R.id.logCount).text =
            activity.getString(R.string.log_count, entries.size, bytesLabel(TtsLog.bytes()))
        if (shown.isEmpty()) return
        if (follow) list.scrollToPosition(shown.lastIndex)
        else if (reset) list.scrollToPosition(0)
    }

    private fun toggle(level: LogLevel) {
        levels = toggleLogLevel(levels, level)
        if (isVisible()) render(follow = false, reset = true)
    }

    fun exportText(): String? {
        val text = TtsLog.text()
        if (text.isBlank()) {
            Toast.makeText(activity, activity.getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
            return null
        }
        return text
    }

    private fun copyAll() {
        val text = TtsLog.text()
        if (text.isBlank()) {
            Toast.makeText(activity, activity.getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", text))
        Toast.makeText(activity, activity.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyPath() {
        val path = TtsLog.path()
        if (path.isBlank()) {
            Toast.makeText(activity, activity.getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", path))
        Toast.makeText(activity, activity.getString(R.string.log_path_copied), Toast.LENGTH_SHORT).show()
    }

    private fun copyLine(line: LogLine) {
        val text = listOf(line.date, line.time, line.level.name, line.module, line.message)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", text))
        Toast.makeText(activity, activity.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun paintBanner() {
        val running = ServiceStates.store.current().running
        val tone = if (running) color(R.color.teal) else color(R.color.text_sub)
        activity.findViewById<TextView>(R.id.logBannerText).apply {
            text = activity.getString(
                if (running) R.string.log_service_on else R.string.log_service_off,
                Process.myPid(),
            )
            setTextColor(tone)
        }
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        activity.findViewById<TextView>(R.id.logBannerRam).apply {
            text = activity.getString(R.string.log_ram, "%.1f".format(Locale.US, memory.totalPss / 1024.0))
            setTextColor(tone)
        }
        activity.findViewById<View>(R.id.logBannerDot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (running) color(R.color.teal_live) else color(R.color.slate))
    }

    private fun paintChip(id: Int, level: LogLevel?, count: Int) {
        val view = activity.findViewById<TextView>(id)
        val selected = if (level == null) levels.size == enumValues<LogLevel>().size else level in levels
        val label = level?.name ?: activity.getString(R.string.filter_all)
        view.text = if (level == null || count > 0) "$label  $count" else label
        view.setTextColor(if (selected) Color.WHITE else color(R.color.slate_text))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) color(R.color.teal_press) else color(R.color.mist))
        }
        val dotColor = when (level) {
            LogLevel.INFO -> color(R.color.info)
            LogLevel.WARN -> color(R.color.amber_mark)
            LogLevel.ERROR -> color(R.color.rose_hot)
            LogLevel.DEBUG -> color(R.color.violet)
            null -> 0
        }
        if (dotColor == 0) {
            view.setCompoundDrawables(null, null, null, null)
        } else {
            val size = dp(6).coerceAtLeast(1)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
                setBounds(0, 0, size, size)
            }
            view.setCompoundDrawables(dot, null, null, null)
            view.compoundDrawablePadding = dp(4)
        }
    }

    private fun bytesLabel(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${(bytes + 512) / 1024} KB"

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}

private class LogRowAdapter(
    private val onLongPress: (LogLine) -> Unit,
) : RecyclerView.Adapter<LogRowAdapter.Holder>() {
    private val rows = ArrayList<Pair<Int, LogLine>>()

    fun submit(items: List<Pair<Int, LogLine>>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_line, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (index, line) = rows[position]
        holder.bind(index, line, position == 0, onLongPress)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val divider: View = view.findViewById(R.id.logRowDivider)
        private val date: TextView = view.findViewById(R.id.logRowDate)
        private val time: TextView = view.findViewById(R.id.logRowTime)
        private val level: TextView = view.findViewById(R.id.logRowLevel)
        private val module: TextView = view.findViewById(R.id.logRowModule)
        private val index: TextView = view.findViewById(R.id.logRowIndex)
        private val message: TextView = view.findViewById(R.id.logRowMessage)

        fun bind(number: Int, line: LogLine, first: Boolean, onLongPress: (LogLine) -> Unit) {
            itemView.setBackgroundColor(rowColor(line.level))
            divider.visibility = if (first) View.GONE else View.VISIBLE
            date.visibility = if (line.date.isBlank()) View.GONE else View.VISIBLE
            date.text = line.date
            time.text = line.time.ifBlank { "--:--:--.---" }
            level.text = line.level.name
            paintLevel(line.level)
            module.text = if (line.module.isBlank()) "" else "[${line.module}]"
            index.text = "#%03d".format(Locale.US, number)
            message.text = line.message
            itemView.setOnLongClickListener {
                onLongPress(line)
                true
            }
        }

        private fun paintLevel(value: LogLevel) {
            val (fill, ink, stroke) = when (value) {
                LogLevel.INFO -> Triple(color(R.color.info_wash), color(R.color.cyan_ink), color(R.color.info_line))
                LogLevel.WARN -> Triple(color(R.color.amber_wash), color(R.color.amber_ink), color(R.color.amber_line))
                LogLevel.ERROR -> Triple(color(R.color.rose_wash), color(R.color.rose_ink), color(R.color.rose_line))
                LogLevel.DEBUG -> Triple(color(R.color.violet_wash), color(R.color.violet_ink), color(R.color.violet_line))
            }
            level.setTextColor(ink)
            level.background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(fill)
                setStroke(dp(1).coerceAtLeast(1), stroke)
            }
        }

        private fun color(id: Int) = ContextCompat.getColor(itemView.context, id)

        private fun rowColor(value: LogLevel): Int = when (value) {
            LogLevel.INFO -> Color.WHITE
            LogLevel.WARN -> color(R.color.amber_wash)
            LogLevel.ERROR -> color(R.color.rose_wash)
            LogLevel.DEBUG -> color(R.color.violet_wash)
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density).toInt()
    }
}
