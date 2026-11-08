package com.genie.tts

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
    private val isVisible: () -> Boolean,
    private val notificationsAllowed: () -> Boolean,
) {
    private val status: TextView = activity.findViewById(R.id.serviceStatus)
    private val uptime: View = activity.findViewById(R.id.serviceUptime)
    private val uptimeValue: TextView = activity.findViewById(R.id.serviceUptimeValue)
    private val dot: View = activity.findViewById(R.id.serviceDot)
    private val notice: TextView = activity.findViewById(R.id.serviceNotice)
    private val toggle: TextView = activity.findViewById(R.id.serviceToggle)
    val port: EditText = activity.findViewById(R.id.servicePort)
    val localUrl: TextView = activity.findViewById(R.id.serviceLocalUrl)
    val lanUrl: TextView = activity.findViewById(R.id.serviceLanUrl)
    private val lanRow: View = activity.findViewById(R.id.serviceLanRow)
    private val ids: TextView = activity.findViewById(R.id.serviceIds)
    private val requests: TextView = activity.findViewById(R.id.serviceStatRequests)
    private val failures: TextView = activity.findViewById(R.id.serviceStatFailures)
    private val audio: TextView = activity.findViewById(R.id.serviceStatAudio)
    private val drawerState: TextView = activity.findViewById(R.id.drawerServiceState)
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
        val running = TtsRuntime.running
        val problem = TtsRuntime.error
        val switching = TtsRuntime.switching
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
                open -> Color.parseColor("#10B981")
                !running && problem.isNullOrBlank() && !switching -> Color.parseColor("#CBD5E1")
                else -> color(R.color.amber)
            },
        )
        notice.visibility = if (running && !notificationsAllowed()) View.VISIBLE else View.GONE
        localUrl.text = url("127.0.0.1")
        val addresses = interfaceAddresses().filter { it != "127.0.0.1" }
        lanRow.visibility = if (addresses.isEmpty()) View.GONE else View.VISIBLE
        lanUrl.text = addresses.joinToString("\n") { url(it) }
        ids.text = roster.joinToString("、") { it.id }.ifBlank { activity.getString(R.string.service_ids_empty) }
        requests.text = TtsRuntime.requests.toString()
        failures.text = TtsRuntime.failures.toString()
        failures.setTextColor(if (TtsRuntime.failures > 0) Color.parseColor("#BE123C") else color(R.color.text_main))
        audio.text = audioLength(TtsRuntime.audioMs)
        drawerState.text = if (running) "运行中" else activity.getString(R.string.service_stopped)
        drawerState.setTextColor(color(if (running) R.color.primary else R.color.text_sub))
        drawerState.setBackgroundResource(if (running) R.drawable.bg_pill else R.drawable.bg_prosody_idle)
        toggle.setText(if (running) R.string.service_stop else R.string.service_start)
        toggle.setBackgroundResource(if (running) R.drawable.bg_service_stop else R.drawable.bg_service_start)
        toggle.setTextColor(if (running) Color.parseColor("#BE123C") else Color.WHITE)
        toggle.isEnabled = !switching
        toggle.alpha = if (switching) 0.45f else 1f
        val shown = TtsRuntime.port.toString()
        if (!port.hasFocus() && port.text.toString() != shown) port.setText(shown)
    }

    fun stop() {
        handler.removeCallbacks(ticker)
    }

    fun scheduleUptime() {
        handler.removeCallbacks(ticker)
        val started = TtsRuntime.startedAt
        val show = TtsRuntime.running && started > 0L && isVisible() && !activity.isDestroyed
        if (!show) return
        val elapsed = SystemClock.elapsedRealtime() - started
        handler.postDelayed(ticker, (1000 - (elapsed % 1000)).coerceIn(32, 1000))
    }

    private fun paintUptime() {
        val started = TtsRuntime.startedAt
        val show = TtsRuntime.running && started > 0L
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

    private fun url(address: String): String = "http://$address:${TtsRuntime.port}/tts"

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
