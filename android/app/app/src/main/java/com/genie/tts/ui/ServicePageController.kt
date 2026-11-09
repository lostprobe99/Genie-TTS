package com.genie.tts.ui

import com.genie.tts.databinding.ActivityMainBinding
import com.genie.tts.R
import com.genie.tts.character.CharacterInfo
import com.genie.tts.service.ServiceState
import com.genie.tts.service.ServiceStates
import com.genie.tts.service.interfaceAddresses
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/** Owns service-page views, session statistics and uptime painting. */
internal class ServicePageController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val isVisible: () -> Boolean,
    private val notificationsAllowed: () -> Boolean,
) {
    private val status: TextView = binding.servicePage.serviceStatus
    private val uptime: View = binding.servicePage.serviceUptime
    private val uptimeValue: TextView = binding.servicePage.serviceUptimeValue
    private val dot: View = binding.servicePage.serviceDot
    private val notice: TextView = binding.servicePage.serviceNotice
    private val toggle: TextView = binding.servicePage.serviceToggle
    val port: EditText = binding.servicePage.servicePort
    val localUrl: TextView = binding.servicePage.serviceLocalUrl
    val lanUrl: TextView = binding.servicePage.serviceLanUrl
    private val lanRow: View = binding.servicePage.serviceLanRow
    private val ids: TextView = binding.servicePage.serviceIds
    private val requests: TextView = binding.servicePage.serviceStatRequests
    private val failures: TextView = binding.servicePage.serviceStatFailures
    private val audio: TextView = binding.servicePage.serviceStatAudio
    private val drawerState: TextView = binding.drawerServiceState
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (activity.isDestroyed) return
            paintUptime()
            scheduleUptime()
        }
    }

    fun bind(onToggle: () -> Unit, onCommitPort: () -> Unit) {
        toggle.setOnClickListener { onToggle() }
        port.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                view.clearFocus()
                true
            } else {
                false
            }
        }
        port.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onCommitPort() }
    }

    fun render(roster: List<CharacterInfo>) {
        val state = ServiceStates.store.current()
        val running = state.running
        val problem = state.error
        val switching = state.switching
        status.text = when {
            switching -> activity.getString(R.string.service_switching)
            running -> activity.getString(R.string.service_running)
            !problem.isNullOrBlank() -> problem
            else -> activity.getString(R.string.service_stopped)
        }
        val open = running && !switching
        paintUptime()
        scheduleUptime()
        dot.backgroundTintList = ColorStateList.valueOf(
            when {
                open -> color(R.color.green)
                !running && problem.isNullOrBlank() && !switching -> color(R.color.mist_line)
                else -> color(R.color.amber)
            },
        )
        notice.visibility = if (running && !notificationsAllowed()) View.VISIBLE else View.GONE
        localUrl.text = url("127.0.0.1", state)
        val addresses = interfaceAddresses().filter { it != "127.0.0.1" }
        lanRow.visibility = if (addresses.isEmpty()) View.GONE else View.VISIBLE
        lanUrl.text = addresses.joinToString("\n") { url(it, state) }
        ids.text = roster.joinToString("、") { it.id }.ifBlank { activity.getString(R.string.service_ids_empty) }
        requests.text = state.requests.toString()
        failures.text = state.failures.toString()
        failures.setTextColor(if (state.failures > 0) color(R.color.rose_ink) else color(R.color.text_main))
        audio.text = audioLength(state.audioMs)
        drawerState.setText(if (running) R.string.service_drawer_running else R.string.service_stopped)
        drawerState.setTextColor(color(if (running) R.color.primary else R.color.text_sub))
        drawerState.setBackgroundResource(if (running) R.drawable.bg_pill else R.drawable.bg_prosody_idle)
        toggle.setText(if (running) R.string.service_stop else R.string.service_start)
        toggle.setBackgroundResource(if (running) R.drawable.bg_service_stop else R.drawable.bg_service_start)
        toggle.setTextColor(if (running) color(R.color.rose_ink) else Color.WHITE)
        toggle.isEnabled = !switching
        toggle.alpha = if (switching) 0.45f else 1f
        val shown = state.port.toString()
        if (!port.hasFocus() && port.text.toString() != shown) port.setText(shown)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    fun scheduleUptime() {
        handler.removeCallbacks(ticker)
        val state = ServiceStates.store.current()
        val started = state.startedAt
        val show = state.running && started > 0L && isVisible() && !activity.isDestroyed
        if (!show) return
        val elapsed = SystemClock.elapsedRealtime() - started
        handler.postDelayed(ticker, (1000 - (elapsed % 1000)).coerceIn(32, 1000))
    }

    private fun paintUptime() {
        val state = ServiceStates.store.current()
        val started = state.startedAt
        val show = state.running && started > 0L
        uptime.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val total = ((SystemClock.elapsedRealtime() - started) / 1000).coerceAtLeast(0)
        uptimeValue.text = "%02d:%02d:%02d".format(
            Locale.US,
            total / 3600,
            (total % 3600) / 60,
            total % 60,
        )
    }

    private fun url(address: String, state: ServiceState): String = "http://$address:${state.port}/tts"

    private fun audioLength(ms: Double): String {
        val total = (ms / 1000.0 + 0.5).toLong().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        else "%d:%02d".format(Locale.US, minutes, seconds)
    }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}
