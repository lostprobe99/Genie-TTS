package com.genie.onnxdemo

import kotlin.math.abs
import kotlin.math.max

internal data class SynthParams(
    val speed: Double,
    val temperature: Double,
    val topK: Int,
    val repetitionPenalty: Double,
    val minSentence: Int,
    val maxSentence: Int,
    val maxSteps: Int,
    val threads: Int,
    val leadingPeriod: Boolean,
) {
    val samplingIsBaked: Boolean
        get() = near(temperature, 1.0) && topK == 15 && near(repetitionPenalty, 1.35)

    fun samplingStamp(): String {
        val temperatureBits = (temperature * 100).toInt()
        val penaltyBits = (repetitionPenalty * 100).toInt()
        return "t$temperatureBits-k$topK-p$penaltyBits"
    }

    fun splitMax(): Int = max(maxSentence, minSentence)

    fun describe(actualThreads: Int): String {
        val period = if (leadingPeriod) "开" else "关"
        return "参数 语速 ${"%.2f".format(speed)}，温度 ${"%.2f".format(temperature)}，TopK $topK，" +
            "重复惩罚 ${"%.2f".format(repetitionPenalty)}，分句 $minSentence–${splitMax()}，" +
            "句首句号 $period，步数 $maxSteps，线程 $actualThreads"
    }

    companion object {
        fun speed(progress: Int): Double = 0.60 + progress * 0.05
        fun temperature(progress: Int): Double = 0.20 + progress * 0.05
        fun topK(progress: Int): Int = 1 + progress
        fun penalty(progress: Int): Double = 1.00 + progress * 0.05
        fun minSentence(progress: Int): Int = 1 + progress
        fun maxSentence(progress: Int): Int = 10 + progress
        fun maxSteps(progress: Int): Int = 100 + progress * 20

        fun fromProgress(
            speed: Int,
            temperature: Int,
            topK: Int,
            penalty: Int,
            minSentence: Int,
            maxSentence: Int,
            maxSteps: Int,
            threads: Int,
            leadingPeriod: Boolean,
        ): SynthParams = SynthParams(
            speed = speed(speed),
            temperature = temperature(temperature),
            topK = topK(topK),
            repetitionPenalty = penalty(penalty),
            minSentence = minSentence(minSentence),
            maxSentence = maxSentence(maxSentence),
            maxSteps = maxSteps(maxSteps),
            threads = threads,
            leadingPeriod = leadingPeriod,
        )
    }
}

private fun near(left: Double, right: Double): Boolean = abs(left - right) < 0.001
