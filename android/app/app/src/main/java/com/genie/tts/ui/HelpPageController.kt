package com.genie.tts.ui

import com.genie.tts.R
import com.genie.tts.service.ServiceStates
import com.genie.tts.speech.Release
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Owns the help page's category, expanded-topic and search state. */
internal class HelpPageController(
    private val activity: AppCompatActivity,
    private val isVisible: () -> Boolean,
    private val versionName: () -> String,
) {
    private var category = "all"
    private val open = mutableMapOf<String, Boolean>()
    private val root: ScrollView = activity.findViewById(R.id.helpPage)
    private val query: EditText = activity.findViewById(R.id.helpQuery)

    fun bind(
        onBack: () -> Unit,
        onRefresh: () -> Unit,
        onDocs: () -> Unit,
        onCopyEndpoint: () -> Unit,
        onRepo: () -> Unit,
        onProsody: () -> Unit,
        onOpenLogs: () -> Unit,
    ) {
        activity.findViewById<View>(R.id.helpBack).setOnClickListener { onBack() }
        activity.findViewById<View>(R.id.helpRefresh).setOnClickListener { onRefresh() }
        activity.findViewById<View>(R.id.helpDocs).setOnClickListener { onDocs() }
        listOf(
            R.id.helpFilterAll to "all",
            R.id.helpFilterStarter to "starter",
            R.id.helpFilterLegado to "legado",
            R.id.helpFilterBattery to "battery",
            R.id.helpFilterModel to "model",
            R.id.helpFilterService to "service",
        ).forEach { (id, key) ->
            activity.findViewById<View>(id).setOnClickListener { pick(key) }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                activity.findViewById<View>(R.id.helpQueryClear).visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (isVisible()) render(true)
            }
        })
        activity.findViewById<View>(R.id.helpQueryClear).setOnClickListener { query.setText("") }
        activity.findViewById<View>(R.id.helpRepo).setOnClickListener { onRepo() }
        activity.findViewById<View>(R.id.helpProsody).setOnClickListener { onProsody() }
        activity.findViewById<View>(R.id.helpOpenLogs).setOnClickListener { onOpenLogs() }
        copyEndpoint = onCopyEndpoint
    }

    private var copyEndpoint: () -> Unit = {}

    fun render(toTop: Boolean) {
        val oldScrollY = root.scrollY
        paintChips()
        val shown = fillHelpList(
            activity.findViewById(R.id.helpTopics),
            ServiceStates.store.current().port,
            query.text.toString(),
            category,
            open,
            onToggle = { title ->
                open[title] = open[title] != true
                render(false)
            },
            onCopy = copyEndpoint,
        )
        activity.findViewById<View>(R.id.helpEmpty).visibility = if (shown) View.GONE else View.VISIBLE
        activity.findViewById<TextView>(R.id.helpProsodySub).text = "${Release.TAG} / prosody.zip"
        activity.findViewById<TextView>(R.id.helpVersion).text = "GenieTTS v${versionName()}"
        root.post { root.scrollTo(0, if (toTop) 0 else oldScrollY) }
    }

    private fun pick(key: String) {
        category = key
        if (isVisible()) render(true)
    }

    private fun paintChips() {
        listOf(
            R.id.helpFilterAll to "all",
            R.id.helpFilterStarter to "starter",
            R.id.helpFilterLegado to "legado",
            R.id.helpFilterBattery to "battery",
            R.id.helpFilterModel to "model",
            R.id.helpFilterService to "service",
        ).forEach { (id, key) ->
            val view = activity.findViewById<TextView>(id)
            val selected = category == key
            view.setTextColor(if (selected) Color.WHITE else 0xFF475569.toInt())
            view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) 0xFF0D7384.toInt() else Color.WHITE)
            }
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
