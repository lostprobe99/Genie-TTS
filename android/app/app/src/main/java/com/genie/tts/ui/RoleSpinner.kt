package com.genie.tts.ui

import com.genie.tts.R
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 主屏上的当前角色。选中后做什么，由外面决定。 */
internal class RoleSpinner(activity: AppCompatActivity) {
    private val placeholder: TextView = activity.findViewById(R.id.characterPlaceholder)
    private val spinner: Spinner = activity.findViewById(R.id.characters)
    private var suppress = false
    private var generation = 0
    private var onSelect: (Int) -> Unit = {}

    init {
        spinner.setPopupBackgroundResource(R.color.card)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppress) return
                onSelect(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    fun bind(onSelect: (Int) -> Unit) {
        this.onSelect = onSelect
    }

    fun render(names: List<String>, ids: List<String>, selectedId: String?, unselected: String) {
        if (names.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            spinner.visibility = View.GONE
            return
        }
        placeholder.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        val labels = names + unselected
        val ticket = ++generation
        suppress = true
        val adapter = ArrayAdapter(spinner.context, R.layout.spinner_selected, labels)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        spinner.adapter = adapter
        val index = ids.indexOfFirst { it == selectedId }
        spinner.setSelection(if (index >= 0) index else names.size, false)
        spinner.post { if (ticket == generation) suppress = false }
    }

    fun setEnabled(enabled: Boolean) {
        spinner.isEnabled = enabled
    }

    fun click() {
        spinner.performClick()
    }
}
