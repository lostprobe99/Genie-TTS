package com.genie.onnxdemo

import kotlin.math.max

internal data class SynthParams(
    val speed: Double,
    val minSentence: Int,
    val maxSentence: Int,
    val maxSteps: Int,
    val threads: Int,
    val leadingPeriod: Boolean,
) {
    fun splitMax(): Int = max(maxSentence, minSentence)

    fun describe(actualThreads: Int): String {
        val period = if (leadingPeriod) "开" else "关"
        return "参数 语速 ${"%.2f".format(speed)}，分句 $minSentence–${splitMax()}，" +
            "句首句号 $period，步数 $maxSteps，线程 $actualThreads"
    }

    companion object {
        fun speed(progress: Int): Double = 0.60 + progress * 0.05
        fun minSentence(progress: Int): Int = 1 + progress
        fun maxSentence(progress: Int): Int = 10 + progress
        fun maxSteps(progress: Int): Int = 100 + progress * 20

        fun fromProgress(
            speed: Int,
            minSentence: Int,
            maxSentence: Int,
            maxSteps: Int,
            threads: Int,
            leadingPeriod: Boolean,
        ): SynthParams = SynthParams(
            speed = speed(speed),
            minSentence = minSentence(minSentence),
            maxSentence = maxSentence(maxSentence),
            maxSteps = maxSteps(maxSteps),
            threads = threads,
            leadingPeriod = leadingPeriod,
        )
    }
}
