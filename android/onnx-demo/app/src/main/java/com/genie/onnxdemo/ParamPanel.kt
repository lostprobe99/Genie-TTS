package com.genie.onnxdemo

import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

internal class ParamPanel(private val activity: AppCompatActivity, private val filesDir: File) {
    private val toggle = activity.findViewById<TextView>(R.id.paramsToggle)
    private val panel = activity.findViewById<android.view.View>(R.id.paramsPanel)
    private val speed = activity.findViewById<SeekBar>(R.id.speed)
    private val temperature = activity.findViewById<SeekBar>(R.id.temperature)
    private val topK = activity.findViewById<SeekBar>(R.id.topK)
    private val penalty = activity.findViewById<SeekBar>(R.id.penalty)
    private val minSentence = activity.findViewById<SeekBar>(R.id.minSentence)
    private val maxSentence = activity.findViewById<SeekBar>(R.id.maxSentence)
    private val maxSteps = activity.findViewById<SeekBar>(R.id.maxSteps)
    private val threads = activity.findViewById<SeekBar>(R.id.threads)
    private val leading = activity.findViewById<CheckBox>(R.id.leadingPeriod)
    private var open = false

    fun bind() {
        val saved = readFile()
        speed.progress = saved.speed
        temperature.progress = saved.temperature
        topK.progress = saved.topK
        penalty.progress = saved.penalty
        minSentence.progress = saved.minSentence
        maxSentence.progress = saved.maxSentence
        maxSteps.progress = saved.maxSteps
        threads.progress = saved.threads
        leading.isChecked = saved.leading
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
        listOf(speed, temperature, topK, penalty, minSentence, maxSentence, maxSteps, threads)
            .forEach { it.setOnSeekBarChangeListener(listener) }
        leading.setOnCheckedChangeListener { _, _ ->
            refreshLabels()
            save()
        }
        toggle.setOnClickListener {
            open = !open
            panel.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            refreshLabels()
            save()
        }
    }

    fun read(): SynthParams = SynthParams.fromProgress(
        speed = speed.progress,
        temperature = temperature.progress,
        topK = topK.progress,
        penalty = penalty.progress,
        minSentence = minSentence.progress,
        maxSentence = maxSentence.progress,
        maxSteps = maxSteps.progress,
        threads = threads.progress,
        leadingPeriod = leading.isChecked,
    )

    fun setEnabled(enabled: Boolean) {
        listOf(speed, temperature, topK, penalty, minSentence, maxSentence, maxSteps, threads)
            .forEach { it.isEnabled = enabled }
        leading.isEnabled = enabled
    }

    private fun refreshLabels() {
        val params = read()
        activity.findViewById<TextView>(R.id.speedLabel).text =
            activity.getString(R.string.speed_label, params.speed)
        activity.findViewById<TextView>(R.id.temperatureLabel).text =
            activity.getString(R.string.temperature_label, params.temperature)
        activity.findViewById<TextView>(R.id.topKLabel).text =
            activity.getString(R.string.topk_label, params.topK)
        activity.findViewById<TextView>(R.id.penaltyLabel).text =
            activity.getString(R.string.penalty_label, params.repetitionPenalty)
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
                "temperature=${temperature.progress}",
                "topK=${topK.progress}",
                "penalty=${penalty.progress}",
                "minSentence=${minSentence.progress}",
                "maxSentence=${maxSentence.progress}",
                "maxSteps=${maxSteps.progress}",
                "threads=${threads.progress}",
                "leading=${if (leading.isChecked) 1 else 0}",
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
            temperature = values["temperature"] ?: 16,
            topK = values["topK"] ?: 14,
            penalty = values["penalty"] ?: 7,
            minSentence = values["minSentence"] ?: 4,
            maxSentence = values["maxSentence"] ?: 30,
            maxSteps = values["maxSteps"] ?: 20,
            threads = values["threads"] ?: 0,
            leading = (values["leading"] ?: 1) != 0,
            open = (values["open"] ?: 0) != 0,
        )
    }

    private data class Saved(
        val speed: Int = 8,
        val temperature: Int = 16,
        val topK: Int = 14,
        val penalty: Int = 7,
        val minSentence: Int = 4,
        val maxSentence: Int = 30,
        val maxSteps: Int = 20,
        val threads: Int = 0,
        val leading: Boolean = true,
        val open: Boolean = false,
    )

    companion object {
        private const val FILE = "synth-params.txt"
    }
}
