package com.genie.onnxdemo

import kotlin.math.max
import kotlin.math.roundToInt

/** 把语义帧按语速重采样。小于 1 会重复帧，声音变慢，音高不变。 */
internal fun stretchTokens(tokens: LongArray, speed: Double): LongArray {
    if (tokens.isEmpty() || speed == 1.0) return tokens
    val count = max(1, (tokens.size / speed).roundToInt())
    if (count == tokens.size) return tokens
    return LongArray(count) { index ->
        val source = ((index + 0.5) * tokens.size / count).toInt().coerceIn(0, tokens.lastIndex)
        tokens[source]
    }
}
