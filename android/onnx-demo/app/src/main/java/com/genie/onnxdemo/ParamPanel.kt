package com.genie.onnxdemo

import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

internal class ParamPanel(private val activity: AppCompatActivity, private val filesDir: File) {
    private val toggle = activity.findViewById<TextView>(R.id.paramsToggle)
    private val panel = activity.findViewById<android.view.View>(R.id.paramsPanel)
    private val speed = activity.findViewById<SeekBar>(R.id.speed)
    private val minSentence = activity.findViewById<SeekBar>(R.id.minSentence)
    private val maxSentence = activity.findViewById<SeekBar>(R.id.maxSentence)
    private val maxSteps = activity.findViewById<SeekBar>(R.id.maxSteps)
    private val threads = activity.findViewById<SeekBar>(R.id.threads)
    private var open = false

    fun bind() {
        val saved = readFile()
        speed.progress = saved.speed
        minSentence.progress = saved.minSentence
        maxSentence.progress = saved.maxSentence
        maxSteps.progress = saved.maxSteps
        threads.progress = saved.threads
        open = saved.open
        panel.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
        refreshLabels()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshLabels()
                if (fromUser) save()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        listOf(speed, minSentence, maxSentence, maxSteps, threads)
            .forEach { it.setOnSeekBarChangeListener(listener) }
        toggle.setOnClickListener {
            open = !open
            panel.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            refreshLabels()
            save()
        }
    }

    fun read(): SynthParams = SynthParams.fromProgress(
        speed = speed.progress,
        minSentence = minSentence.progress,
        maxSentence = maxSentence.progress,
        maxSteps = maxSteps.progress,
        threads = threads.progress,
    )

    fun setEnabled(enabled: Boolean) {
        listOf(speed, minSentence, maxSentence, maxSteps, threads)
            .forEach { it.isEnabled = enabled }
    }

    private fun refreshLabels() {
        val params = read()
        activity.findViewById<TextView>(R.id.speedLabel).text =
            activity.getString(R.string.speed_label, params.speed)
        activity.findViewById<TextView>(R.id.minSentenceLabel).text =
            activity.getString(R.string.min_sentence_label, params.minSentence)
        activity.findViewById<TextView>(R.id.maxSentenceLabel).text =
            activity.getString(R.string.max_sentence_label, params.splitMax())
        activity.findViewById<TextView>(R.id.maxStepsLabel).text =
            activity.getString(R.string.max_steps_label, params.maxSteps)
        activity.findViewById<TextView>(R.id.threadsLabel).text = if (params.threads == 0) {
            activity.getString(R.string.threads_auto)
        } else {
            activity.getString(R.string.threads_fixed, params.threads)
        }
        toggle.text = activity.getString(R.string.params_toggle, params.speed, if (open) "▾" else "▸")
    }

    private fun save() {
        File(filesDir, FILE).writeText(
            listOf(
                "speed=${speed.progress}",
                "minSentence=${minSentence.progress}",
                "maxSentence=${maxSentence.progress}",
                "maxSteps=${maxSteps.progress}",
                "threads=${threads.progress}",
                "open=${if (open) 1 else 0}",
            ).joinToString("\n")
        )
    }

    private fun readFile(): Saved {
        val file = File(filesDir, FILE)
        if (!file.isFile) {
            val legacy = File(filesDir, "speech-speed.txt").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            return Saved(speed = legacy?.coerceIn(0, 16) ?: 8)
        }
        val values = HashMap<String, Int>()
        file.readLines().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) values[parts[0]] = parts[1].toIntOrNull() ?: return@forEach
        }
        return Saved(
            speed = values["speed"] ?: 8,
            minSentence = values["minSentence"] ?: 4,
            maxSentence = values["maxSentence"] ?: 30,
            maxSteps = values["maxSteps"] ?: 20,
            threads = values["threads"] ?: 0,
            open = (values["open"] ?: 0) != 0,
        )
    }

    private data class Saved(
        val speed: Int = 8,
        val minSentence: Int = 4,
        val maxSentence: Int = 30,
        val maxSteps: Int = 20,
        val threads: Int = 0,
        val open: Boolean = false,
    )

    companion object {
        private const val FILE = "synth-params.txt"
    }
}
