package com.genie.onnxdemo.g2p

/** Spread token rows onto phonemes the way GetPhonesAndBert._expand_roberta_output does. */
object BertAlign {
    const val DIM = 1024

    fun expand(features: FloatArray, rows: Int, word2ph: IntArray, numPhones: Int): FloatArray? {
        if (rows <= 0 || numPhones <= 0 || features.size != rows * DIM) return null
        if (rows == numPhones) return features
        val body = when (rows) {
            word2ph.size + 2 -> features.copyOfRange(DIM, features.size - DIM)
            word2ph.size -> features
            else -> return null
        }
        if (body.size != word2ph.size * DIM) return null
        var total = 0
        for (count in word2ph) {
            if (count <= 0) return null
            total += count
        }
        if (total != numPhones) return null
        val out = FloatArray(numPhones * DIM)
        var source = 0
        var dest = 0
        for (count in word2ph) {
            repeat(count) {
                System.arraycopy(body, source, out, dest, DIM)
                dest += DIM
            }
            source += DIM
        }
        return out
    }
}
