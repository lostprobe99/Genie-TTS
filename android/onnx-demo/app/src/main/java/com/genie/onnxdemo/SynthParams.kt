package com.genie.onnxdemo

import kotlin.math.max

internal data class SynthParams(
    val speed: Double,
    val minSentence: Int,
    val maxSentence: Int,
    val threads: Int,
) {
    fun splitMax(): Int = max(maxSentence, minSentence)

    fun describe(actualThreads: Int): String {
        return "参数 语速 ${"%.2f".format(speed)}，分句 $minSentence–${splitMax()}，线程 $actualThreads"
    }

    companion object {
        fun speed(progress: Int): Double = 0.60 + progress * 0.05
        fun minSentence(progress: Int): Int = 1 + progress
        fun maxSentence(progress: Int): Int = 10 + progress

        fun fromProgress(
            speed: Int,
            minSentence: Int,
            maxSentence: Int,
            threads: Int,
        ): SynthParams = SynthParams(
            speed = speed(speed),
            minSentence = minSentence(minSentence),
            maxSentence = maxSentence(maxSentence),
            threads = threads,
        )
    }
}
