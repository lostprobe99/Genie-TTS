package com.genie.tts.ui

import com.genie.tts.databinding.ActivityMainBinding
import com.genie.tts.databinding.ItemLogLineBinding
import androidx.core.content.ContextCompat
import com.genie.tts.R
import com.genie.tts.core.DEFAULT_LOG_LEVELS
import com.genie.tts.core.LogLevel
import com.genie.tts.core.LogLine
import com.genie.tts.core.TtsLog
import com.genie.tts.core.newShownLines
import com.genie.tts.core.recentLogText
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/**
 * Owns the logs page's filter state, bindings and rendering.
 *
 * File operations stay in [MainActivity]; this class is only responsible for
 * presenting the current [TtsLog] snapshot.
 *
 * New lines only move the list while auto-scroll is on and the list sits at its
 * last row. Otherwise the shown rows stay put and a button counts what arrived.
 */
internal class LogPageController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val isVisible: () -> Boolean,
) {
    private var levels: Set<LogLevel> = DEFAULT_LOG_LEVELS
    private val query: EditText = binding.logsPage.logQuery
    private val list: RecyclerView = binding.logsPage.logList
    private val empty: TextView = binding.logsPage.logEmpty
    private val end: TextView = binding.logsPage.logEnd
    private val newLines: TextView = binding.logsPage.logNewLines
    private val rows = LogRowAdapter { copyLine(it) }
    private val autoScroll: SwitchCompat = binding.logsPage.logAutoScroll
    private var atBottom = true
    private var shownSeq = 0L

    fun bind(onBack: () -> Unit, onExport: () -> Unit, onClear: () -> Unit) {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = rows
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) {
                    val resume = !atBottom && newLines.visibility == View.VISIBLE && autoScroll.isChecked
                    atBottom = true
                    if (resume) recyclerView.post { if (isVisible()) render(follow = true) }
                } else if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    atBottom = false
                }
            }
        })
        newLines.setOnClickListener { render(follow = true) }
        autoScroll.setOnCheckedChangeListener { _, on ->
            if (on && isVisible()) render(follow = true)
        }
        binding.logsBack.setOnClickListener { onBack() }
        binding.logsRefresh.setOnClickListener {
            TtsLog.reload()
            render(true)
        }
        binding.logsHeaderExport.setOnClickListener { onExport() }
        binding.logsMore.setOnClickListener { copyPath() }
        binding.logsPage.logFilterAll.setOnClickListener {
            levels = enumValues<LogLevel>().toSet()
            render(follow = false, reset = true)
        }
        binding.logsPage.logFilterInfo.setOnClickListener { toggle(LogLevel.INFO) }
        binding.logsPage.logFilterWarn.setOnClickListener { toggle(LogLevel.WARN) }
        binding.logsPage.logFilterError.setOnClickListener { toggle(LogLevel.ERROR) }
        binding.logsPage.logFilterDebug.setOnClickListener { toggle(LogLevel.DEBUG) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.logsPage.logQueryClear.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (isVisible()) render(follow = false, reset = true)
            }
        })
        binding.logsPage.logQueryClear.setOnClickListener { query.setText("") }
        val switchStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        autoScroll.trackTintList =
            android.content.res.ColorStateList(switchStates, intArrayOf(color(R.color.teal_press), color(R.color.mist_line)))
        autoScroll.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        binding.logsPage.logClear.setOnClickListener { onClear() }
        binding.logsPage.logCopy.setOnClickListener { copyAll() }
        binding.logsPage.logExport.setOnClickListener { onExport() }
    }

    /** [TtsLog] 有新行时调用。停在旧位置或关了自动滚动时，列表不动，只数新来了几行。 */
    fun onNewLines() {
        val entries = TtsLog.entries()
        val cleared = entries.isEmpty() || entries.first().seq > shownSeq + 1 || entries.last().seq < shownSeq
        if (cleared || rows.itemCount == 0 || (autoScroll.isChecked && atBottom)) {
            render(follow = autoScroll.isChecked)
            return
        }
        paintCounts(entries)
        val waiting = newShownLines(entries, levels, query.text.toString(), shownSeq)
        newLines.text = activity.getString(R.string.log_new_lines, waiting)
        newLines.visibility = if (waiting > 0) View.VISIBLE else View.GONE
    }

    fun render(follow: Boolean, reset: Boolean = false) {
        val entries = TtsLog.entries()
        val shown = shownLogLines(entries, levels, query.text.toString())
        paintCounts(entries)
        shownSeq = entries.lastOrNull()?.seq ?: 0L
        newLines.visibility = View.GONE
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
        if (shown.isEmpty()) {
            atBottom = true
            return
        }
        if (follow) {
            list.scrollToPosition(shown.lastIndex)
            atBottom = true
        } else if (reset) {
            list.scrollToPosition(0)
            atBottom = false
        }
    }

    private fun paintCounts(entries: List<LogLine>) {
        paintChip(binding.logsPage.logFilterAll, null, entries.size)
        paintChip(binding.logsPage.logFilterInfo, LogLevel.INFO, entries.count { it.level == LogLevel.INFO })
        paintChip(binding.logsPage.logFilterWarn, LogLevel.WARN, entries.count { it.level == LogLevel.WARN })
        paintChip(binding.logsPage.logFilterError, LogLevel.ERROR, entries.count { it.level == LogLevel.ERROR })
        paintChip(binding.logsPage.logFilterDebug, LogLevel.DEBUG, entries.count { it.level == LogLevel.DEBUG })
        paintBanner()
        binding.logsPage.logCount.text =
            activity.getString(R.string.log_count, entries.size, bytesLabel(TtsLog.bytes()))
    }

    private fun toggle(level: LogLevel) {
        levels = toggleLogLevel(levels, level)
        if (isVisible()) render(follow = false, reset = true)
    }

    private fun copyAll() {
        val entries = TtsLog.entries()
        if (entries.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val (text, count) = recentLogText(entries, MAX_COPY_CHARS)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", text))
        val note = if (count < entries.size) {
            activity.getString(R.string.log_copied_recent, count)
        } else {
            activity.getString(R.string.copied)
        }
        Toast.makeText(activity, note, Toast.LENGTH_SHORT).show()
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
        binding.logsPage.logBannerText.apply {
            text = activity.getString(
                if (running) R.string.log_service_on else R.string.log_service_off,
                Process.myPid(),
            )
            setTextColor(tone)
        }
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        binding.logsPage.logBannerRam.apply {
            text = activity.getString(R.string.log_ram, "%.1f".format(Locale.US, memory.totalPss / 1024.0))
            setTextColor(tone)
        }
        binding.logsPage.logBannerDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (running) color(R.color.teal_live) else color(R.color.slate))
    }

    private fun paintChip(view: TextView, level: LogLevel?, count: Int) {
        val selected = if (level == null) levels.size == enumValues<LogLevel>().size else level in levels
        val label = level?.name ?: activity.getString(R.string.filter_all)
        view.text = if (level == null || count > 0) "$label  $count" else label
        view.setTextColor(if (selected) Color.WHITE else color(R.color.slate_text))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = GradientDrawable().apply {
            cornerRadius = activity.dimen(R.dimen.dp_16)
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
            val size = activity.px(R.dimen.dp_6)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
                setBounds(0, 0, size, size)
            }
            view.setCompoundDrawables(dot, null, null, null)
            view.compoundDrawablePadding = activity.px(R.dimen.dp_4)
        }
    }

    private fun bytesLabel(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${(bytes + 512) / 1024} KB"

    private fun color(id: Int) = ContextCompat.getColor(activity, id)

    private companion object {
        /** 剪贴板走 Binder，整段太大会失败。 */
        const val MAX_COPY_CHARS = 200_000
    }
}

private class LogRowAdapter(
    private val onLongPress: (LogLine) -> Unit,
) : RecyclerView.Adapter<LogRowAdapter.Holder>() {
    private val rows = ArrayList<LogLine>()

    fun submit(items: List<LogLine>) {
        val old = rows.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].seq == items[b].seq
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == items[b] && (a == 0) == (b == 0)
        })
        rows.clear()
        rows.addAll(items)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = ItemLogLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position], position == 0, onLongPress)
    }

    class Holder(row: ItemLogLineBinding) : RecyclerView.ViewHolder(row.root) {
        private val divider: View = row.logRowDivider
        private val date: TextView = row.logRowDate
        private val time: TextView = row.logRowTime
        private val level: TextView = row.logRowLevel
        private val module: TextView = row.logRowModule
        private val index: TextView = row.logRowIndex
        private val message: TextView = row.logRowMessage

        fun bind(line: LogLine, first: Boolean, onLongPress: (LogLine) -> Unit) {
            itemView.setBackgroundColor(rowColor(line.level))
            divider.visibility = if (first) View.GONE else View.VISIBLE
            date.visibility = if (line.date.isBlank()) View.GONE else View.VISIBLE
            date.text = line.date
            time.text = line.time.ifBlank { "--:--:--.---" }
            level.text = line.level.name
            paintLevel(line.level)
            module.text = if (line.module.isBlank()) "" else "[${line.module}]"
            index.text = "#%03d".format(Locale.US, line.seq)
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
                cornerRadius = itemView.dimen(R.dimen.dp_4)
                setColor(fill)
                setStroke(itemView.px(R.dimen.dp_1), stroke)
            }
        }

        private fun color(id: Int) = ContextCompat.getColor(itemView.context, id)

        private fun rowColor(value: LogLevel): Int = when (value) {
            LogLevel.INFO -> Color.WHITE
            LogLevel.WARN -> color(R.color.amber_wash)
            LogLevel.ERROR -> color(R.color.rose_wash)
            LogLevel.DEBUG -> color(R.color.violet_wash)
        }

    }
}
