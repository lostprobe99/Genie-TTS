package com.genie.tts.ui

import com.genie.tts.R
import com.genie.tts.TtsRuntime
import com.genie.tts.core.LogLevel
import com.genie.tts.core.LogLine
import com.genie.tts.core.TtsLog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Debug
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
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
    private var levelFilter: LogLevel? = null
    private val query: EditText = activity.findViewById(R.id.logQuery)
    private val list: LinearLayout = activity.findViewById(R.id.logList)
    private val scroll: ScrollView = activity.findViewById(R.id.logScroll)
    private val end: TextView = activity.findViewById(R.id.logEnd)
    val autoScroll: SwitchCompat = activity.findViewById(R.id.logAutoScroll)

    fun bind(
        onBack: () -> Unit,
        onExport: () -> Unit,
        onCopyPath: () -> Unit,
        onClear: () -> Unit,
        onCopyAll: () -> Unit,
    ) {
        activity.findViewById<View>(R.id.logsBack).setOnClickListener { onBack() }
        activity.findViewById<View>(R.id.logsRefresh).setOnClickListener {
            TtsLog.reload()
            render(true)
        }
        activity.findViewById<View>(R.id.logsHeaderExport).setOnClickListener { onExport() }
        activity.findViewById<View>(R.id.logsMore).setOnClickListener { onCopyPath() }
        activity.findViewById<View>(R.id.logFilterAll).setOnClickListener { pick(null) }
        activity.findViewById<View>(R.id.logFilterInfo).setOnClickListener { pick(LogLevel.INFO) }
        activity.findViewById<View>(R.id.logFilterWarn).setOnClickListener { pick(LogLevel.WARN) }
        activity.findViewById<View>(R.id.logFilterError).setOnClickListener { pick(LogLevel.ERROR) }
        activity.findViewById<View>(R.id.logFilterDebug).setOnClickListener { pick(LogLevel.DEBUG) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                activity.findViewById<View>(R.id.logQueryClear).visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (isVisible()) render(false)
            }
        })
        activity.findViewById<View>(R.id.logQueryClear).setOnClickListener { query.setText("") }
        val switchStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        autoScroll.trackTintList =
            android.content.res.ColorStateList(switchStates, intArrayOf(0xFF0A4B56.toInt(), 0xFFCBD5E1.toInt()))
        autoScroll.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        activity.findViewById<View>(R.id.logClear).setOnClickListener { onClear() }
        activity.findViewById<View>(R.id.logCopy).setOnClickListener { onCopyAll() }
        activity.findViewById<View>(R.id.logExport).setOnClickListener { onExport() }
    }

    fun render(follow: Boolean) {
        val entries = TtsLog.entries()
        val text = query.text.toString().trim()
        val shown = entries.mapIndexed { index, line -> (index + 1) to line }.filter { (_, line) ->
            val levelOk = levelFilter == null || line.level == levelFilter
            val haystack = "${line.module} ${line.message}"
            val queryOk = text.isEmpty() || haystack.contains(text, ignoreCase = true)
            levelOk && queryOk
        }
        paintChip(R.id.logFilterAll, null, entries.size)
        paintChip(R.id.logFilterInfo, LogLevel.INFO, entries.count { it.level == LogLevel.INFO })
        paintChip(R.id.logFilterWarn, LogLevel.WARN, entries.count { it.level == LogLevel.WARN })
        paintChip(R.id.logFilterError, LogLevel.ERROR, entries.count { it.level == LogLevel.ERROR })
        paintChip(R.id.logFilterDebug, LogLevel.DEBUG, entries.count { it.level == LogLevel.DEBUG })
        paintBanner()

        val oldScrollY = scroll.scrollY
        list.removeAllViews()
        if (shown.isEmpty()) {
            end.visibility = View.GONE
            list.addView(TextView(activity).apply {
                this.text = if (entries.isEmpty()) "还没有日志" else "没有符合的日志"
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
                setTextColor(color(R.color.text_sub))
                textSize = 13f
            })
        } else {
            end.visibility = View.VISIBLE
            end.text = if (levelFilter == null && text.isEmpty()) "已显示全部日志" else "已显示筛选结果"
            shown.forEachIndexed { position, (index, line) ->
                if (position > 0) list.addView(divider())
                list.addView(row(index, line))
            }
        }
        activity.findViewById<TextView>(R.id.logCount).text =
            "共 ${entries.size} 条 · 占用 ${bytesLabel(TtsLog.bytes())}"
        scroll.post {
            scroll.post {
                val child = scroll.getChildAt(0)
                if (follow && child != null) scroll.scrollTo(0, child.bottom)
                else scroll.scrollTo(0, oldScrollY)
            }
        }
    }

    private fun pick(level: LogLevel?) {
        levelFilter = level
        if (isVisible()) render(false)
    }

    private fun paintBanner() {
        val running = TtsRuntime.running
        val tone = if (running) 0xFF0F766E.toInt() else 0xFF64748B.toInt()
        activity.findViewById<TextView>(R.id.logBannerText).apply {
            text = if (running) "接口开着 · PID ${Process.myPid()}" else "接口未开启 · PID ${Process.myPid()}"
            setTextColor(tone)
        }
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        activity.findViewById<TextView>(R.id.logBannerRam).apply {
            text = "RAM %.1f MB".format(Locale.US, memory.totalPss / 1024.0)
            setTextColor(tone)
        }
        activity.findViewById<View>(R.id.logBannerDot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (running) 0xFF0D9488.toInt() else 0xFF94A3B8.toInt())
    }

    private fun paintChip(id: Int, level: LogLevel?, count: Int) {
        val view = activity.findViewById<TextView>(id)
        val selected = levelFilter == level
        val label = level?.name ?: "全部"
        view.text = if (level == null || count > 0) "$label  $count" else label
        view.setTextColor(if (selected) Color.WHITE else 0xFF475569.toInt())
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) 0xFF0A4B56.toInt() else 0xFFF1F5F9.toInt())
        }
        val dotColor = when (level) {
            LogLevel.INFO -> 0xFF06B6D4.toInt()
            LogLevel.WARN -> 0xFFF59E0B.toInt()
            LogLevel.ERROR -> 0xFFF43F5E.toInt()
            LogLevel.DEBUG -> 0xFFA855F7.toInt()
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

    private fun row(index: Int, line: LogLine): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(rowColor(line.level))
        }
        val meta = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stamp = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun stampChild() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (line.date.isNotBlank()) stamp.addView(stamp(line.date), stampChild())
        stamp.addView(stamp(line.time.ifBlank { "--:--:--.---" }), stampChild())
        meta.addView(stamp)
        meta.addView(
            badge(line.level),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            },
        )
        meta.addView(
            TextView(activity).apply {
                text = if (line.module.isBlank()) "" else "[${line.module}]"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF334155.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) },
        )
        meta.addView(TextView(activity).apply {
            text = "#%03d".format(Locale.US, index)
            textSize = 10f
            setTextColor(0xFF94A3B8.toInt())
        })
        row.addView(meta)
        row.addView(TextView(activity).apply {
            text = line.message
            textSize = 12f
            setTextColor(0xFF334155.toInt())
            setPadding(dp(2), dp(4), 0, 0)
            setTextIsSelectable(true)
        })
        return row
    }

    private fun stamp(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(0xFF94A3B8.toInt())
    }

    private fun badge(level: LogLevel): TextView {
        val (fill, ink, stroke) = when (level) {
            LogLevel.INFO -> Triple(0xFFECFEFF.toInt(), 0xFF0E7490.toInt(), 0xFFA5F3FC.toInt())
            LogLevel.WARN -> Triple(0xFFFFFBEB.toInt(), 0xFFB45309.toInt(), 0xFFFDE68A.toInt())
            LogLevel.ERROR -> Triple(0xFFFFF1F2.toInt(), 0xFFBE123C.toInt(), 0xFFFECDD3.toInt())
            LogLevel.DEBUG -> Triple(0xFFFAF5FF.toInt(), 0xFF7E22CE.toInt(), 0xFFE9D5FF.toInt())
        }
        return TextView(activity).apply {
            text = level.name
            textSize = 10f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(fill)
                setStroke(dp(1).coerceAtLeast(1), stroke)
            }
        }
    }

    private fun divider() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
        setBackgroundColor(0xFFF1F5F9.toInt())
    }

    private fun rowColor(level: LogLevel): Int = when (level) {
        LogLevel.INFO -> Color.WHITE
        LogLevel.WARN -> 0xFFFFFBEB.toInt()
        LogLevel.ERROR -> 0xFFFFF1F2.toInt()
        LogLevel.DEBUG -> 0xFFFAF5FF.toInt()
    }

    private fun bytesLabel(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${(bytes + 512) / 1024} KB"

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
