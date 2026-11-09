package com.genie.tts.ui

import com.genie.tts.databinding.ActivityMainBinding
import androidx.core.content.ContextCompat
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
    private val binding: ActivityMainBinding,
    private val isVisible: () -> Boolean,
    private val versionName: () -> String,
) {
    private var category = "all"
    private val open = mutableMapOf<String, Boolean>()
    private val root: ScrollView = binding.helpPage.helpPage
    private val query: EditText = binding.helpPage.helpQuery

    fun bind(
        onBack: () -> Unit,
        onRefresh: () -> Unit,
        onDocs: () -> Unit,
        onCopyEndpoint: () -> Unit,
        onRepo: () -> Unit,
        onProsody: () -> Unit,
        onOpenLogs: () -> Unit,
    ) {
        binding.helpBack.setOnClickListener { onBack() }
        binding.helpRefresh.setOnClickListener { onRefresh() }
        binding.helpDocs.setOnClickListener { onDocs() }
        listOf(
            binding.helpPage.helpFilterAll to "all",
            binding.helpPage.helpFilterStarter to "starter",
            binding.helpPage.helpFilterLegado to "legado",
            binding.helpPage.helpFilterBattery to "battery",
            binding.helpPage.helpFilterModel to "model",
            binding.helpPage.helpFilterService to "service",
        ).forEach { (view, key) ->
            view.setOnClickListener { pick(key) }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.helpPage.helpQueryClear.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (isVisible()) render(true)
            }
        })
        binding.helpPage.helpQueryClear.setOnClickListener { query.setText("") }
        binding.helpPage.helpRepo.setOnClickListener { onRepo() }
        binding.helpPage.helpProsody.setOnClickListener { onProsody() }
        binding.helpPage.helpOpenLogs.setOnClickListener { onOpenLogs() }
        copyEndpoint = onCopyEndpoint
    }

    private var copyEndpoint: () -> Unit = {}

    fun render(toTop: Boolean) {
        val oldScrollY = root.scrollY
        paintChips()
        val shown = fillHelpList(
            binding.helpPage.helpTopics,
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
        binding.helpPage.helpEmpty.visibility = if (shown) View.GONE else View.VISIBLE
        binding.helpPage.helpProsodySub.text = "${Release.TAG} / prosody.zip"
        binding.helpPage.helpVersion.text = "GenieTTS v${versionName()}"
        root.post { root.scrollTo(0, if (toTop) 0 else oldScrollY) }
    }

    private fun pick(key: String) {
        category = key
        if (isVisible()) render(true)
    }

    private fun paintChips() {
        listOf(
            binding.helpPage.helpFilterAll to "all",
            binding.helpPage.helpFilterStarter to "starter",
            binding.helpPage.helpFilterLegado to "legado",
            binding.helpPage.helpFilterBattery to "battery",
            binding.helpPage.helpFilterModel to "model",
            binding.helpPage.helpFilterService to "service",
        ).forEach { (view, key) ->
            val selected = category == key
            view.setTextColor(if (selected) Color.WHITE else color(R.color.slate_text))
            view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            view.background = GradientDrawable().apply {
                cornerRadius = activity.dimen(R.dimen.dp_16)
                setColor(if (selected) color(R.color.teal_chip) else Color.WHITE)
            }
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}
