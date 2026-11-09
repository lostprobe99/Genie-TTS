package com.genie.tts.speech.g2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.tanh

/**
 * GRU encoder/decoder from EnglishG2P CleanG2p.predict.
 * Weights come from checkpoint20.npz exported as eng_g2p_f32.bin.
 */
class EnglishNeuralG2p(files: G2pFiles) {
    private val graphemeId = HashMap<String, Int>()
    private val phonemeAt: Array<String>
    private val phonemeId = HashMap<String, Int>()
    private val encEmb: Array<FloatArray>
    private val encWIh: Array<FloatArray>
    private val encWHh: Array<FloatArray>
    private val encBIh: FloatArray
    private val encBHh: FloatArray
    private val decEmb: Array<FloatArray>
    private val decWIh: Array<FloatArray>
    private val decWHh: Array<FloatArray>
    private val decBIh: FloatArray
    private val decBHh: FloatArray
    private val fcW: Array<FloatArray>
    private val fcB: FloatArray
    private val unkGrapheme: Int
    private val bosPhoneme: Int
    private val eosPhoneme: Int
    private val hiddenSize: Int

    init {
        GRAPHEMES.forEachIndexed { index, symbol -> graphemeId[symbol] = index }
        phonemeAt = PHONEMES.toTypedArray()
        PHONEMES.forEachIndexed { index, symbol -> phonemeId[symbol] = index }
        unkGrapheme = graphemeId.getValue("<unk>")
        bosPhoneme = phonemeId.getValue("<s>")
        eosPhoneme = phonemeId.getValue("</s>")

        val weights = LinkedHashMap<String, Array<FloatArray>>()
        val flat = ByteBuffer.wrap(files.bytes("eng_g2p_f32.bin")).order(ByteOrder.LITTLE_ENDIAN)
        for (line in files.lines("eng_g2p_shapes.txt")) {
            val tab = line.indexOf('\t')
            val name = line.substring(0, tab)
            val dims = line.substring(tab + 1).split(',').map { it.toInt() }
            val rows = if (dims.size == 1) 1 else dims[0]
            val cols = dims.last()
            val matrix = Array(rows) { FloatArray(cols) }
            for (row in 0 until rows) {
                for (col in 0 until cols) matrix[row][col] = flat.float
            }
            weights[name] = matrix
        }
        encEmb = weights.getValue("enc_emb")
        encWIh = weights.getValue("enc_w_ih")
        encWHh = weights.getValue("enc_w_hh")
        encBIh = weights.getValue("enc_b_ih")[0]
        encBHh = weights.getValue("enc_b_hh")[0]
        decEmb = weights.getValue("dec_emb")
        decWIh = weights.getValue("dec_w_ih")
        decWHh = weights.getValue("dec_w_hh")
        decBIh = weights.getValue("dec_b_ih")[0]
        decBHh = weights.getValue("dec_b_hh")[0]
        fcW = weights.getValue("fc_w")
        fcB = weights.getValue("fc_b")[0]
        hiddenSize = encWHh[0].size
    }

    fun predict(word: String): List<String> {
        val chars = word.lowercase().map { it.toString() } + "</s>"
        val encoded = Array(chars.size) { step ->
            encEmb[graphemeId[chars[step]] ?: unkGrapheme].copyOf()
        }
        var hidden = FloatArray(hiddenSize)
        for (step in encoded.indices) {
            hidden = gruCell(encoded[step], hidden, encWIh, encWHh, encBIh, encBHh)
        }
        var decoder = decEmb[bosPhoneme].copyOf()
        val preds = ArrayList<String>()
        repeat(MAX_STEPS) {
            hidden = gruCell(decoder, hidden, decWIh, decWHh, decBIh, decBHh)
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (index in phonemeAt.indices) {
                var score = fcB[index]
                val row = fcW[index]
                for (col in row.indices) score += hidden[col] * row[col]
                if (score > bestScore) {
                    bestScore = score
                    best = index
                }
            }
            if (best == eosPhoneme) return preds
            preds.add(phonemeAt[best])
            decoder = decEmb[best].copyOf()
        }
        return preds
    }

    private fun gruCell(
        input: FloatArray,
        state: FloatArray,
        weightIh: Array<FloatArray>,
        weightHh: Array<FloatArray>,
        biasIh: FloatArray,
        biasHh: FloatArray,
    ): FloatArray {
        val gates = biasIh.size
        val rznIh = FloatArray(gates)
        val rznHh = FloatArray(gates)
        for (row in 0 until gates) {
            var sumIh = biasIh[row]
            var sumHh = biasHh[row]
            val wIh = weightIh[row]
            val wHh = weightHh[row]
            for (col in input.indices) sumIh += input[col] * wIh[col]
            for (col in state.indices) sumHh += state[col] * wHh[col]
            rznIh[row] = sumIh
            rznHh[row] = sumHh
        }
        val split = gates * 2 / 3
        val half = split / 2
        val next = FloatArray(state.size)
        for (index in 0 until half) {
            val r = sigmoid(rznIh[index] + rznHh[index])
            val z = sigmoid(rznIh[index + half] + rznHh[index + half])
            val n = tanh(rznIh[index + split] + r * rznHh[index + split])
            next[index] = (1f - z) * n + z * state[index]
        }
        return next
    }

    private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

    companion object {
        private const val MAX_STEPS = 20
        private val GRAPHEMES = listOf("<pad>", "<unk>", "</s>") + ('a'..'z').map { it.toString() }
        private val PHONEMES = listOf(
            "<pad>", "<unk>", "<s>", "</s>",
            "AA0", "AA1", "AA2", "AE0", "AE1", "AE2", "AH0", "AH1", "AH2", "AO0", "AO1", "AO2",
            "AW0", "AW1", "AW2", "AY0", "AY1", "AY2", "B", "CH", "D", "DH",
            "EH0", "EH1", "EH2", "ER0", "ER1", "ER2", "EY0", "EY1", "EY2", "F", "G", "HH",
            "IH0", "IH1", "IH2", "IY0", "IY1", "IY2", "JH", "K", "L", "M", "N", "NG",
            "OW0", "OW1", "OW2", "OY0", "OY1", "OY2", "P", "R", "S", "SH", "T", "TH",
            "UH0", "UH1", "UH2", "UW", "UW0", "UW1", "UW2", "V", "W", "Y", "Z", "ZH",
        )
    }
}
