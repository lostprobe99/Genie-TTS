package com.genie.onnxdemo.g2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.tanh

/** g2pM polyphone model: one bidirectional LSTM plus two dense layers. */
class G2pM(files: G2pFiles) {
    private val charToId = HashMap<String, Int>()
    private val idToClass = HashMap<Int, String>()
    private val cedict = HashMap<String, List<String>>()
    private val embeddings: Array<DoubleArray>
    private val weightIh: Array<DoubleArray>
    private val weightHh: Array<DoubleArray>
    private val biasIh: DoubleArray
    private val biasHh: DoubleArray
    private val weightIhR: Array<DoubleArray>
    private val weightHhR: Array<DoubleArray>
    private val biasIhR: DoubleArray
    private val biasHhR: DoubleArray
    private val hidden0: Array<DoubleArray>
    private val bias0: DoubleArray
    private val hidden1: Array<DoubleArray>
    private val bias1: DoubleArray
    private val unk: Int
    private val bos: Int
    private val eos: Int

    init {
        for (line in files.lines("g2pm_chars.txt")) {
            val tab = line.indexOf('\t')
            charToId[line.substring(0, tab)] = line.substring(tab + 1).toInt()
        }
        for (line in files.lines("g2pm_classes.txt")) {
            val tab = line.indexOf('\t')
            idToClass[line.substring(0, tab).toInt()] = line.substring(tab + 1)
        }
        for (line in files.lines("g2pm_cedict.txt")) {
            val tab = line.indexOf('\t')
            cedict[line.substring(0, tab)] = line.substring(tab + 1).split(' ')
        }
        val weights = LinkedHashMap<String, Array<DoubleArray>>()
        val flat = ByteBuffer.wrap(files.bytes("g2pm_f32.bin")).order(ByteOrder.LITTLE_ENDIAN)
        for (line in files.lines("g2pm_shapes.txt")) {
            val tab = line.indexOf('\t')
            val name = line.substring(0, tab)
            val dims = line.substring(tab + 1).split(',').map { it.toInt() }
            val rows = if (dims.size == 1) 1 else dims[0]
            val cols = dims.last()
            val matrix = Array(rows) { DoubleArray(cols) }
            for (row in 0 until rows) {
                for (col in 0 until cols) matrix[row][col] = flat.float.toDouble()
            }
            weights[name] = matrix
        }
        embeddings = weights.getValue("embeddings")
        weightIh = weights.getValue("weight_ih")
        weightHh = weights.getValue("weight_hh")
        biasIh = weights.getValue("bias_ih")[0]
        biasHh = weights.getValue("bias_hh")[0]
        weightIhR = weights.getValue("weight_ih_reverse")
        weightHhR = weights.getValue("weight_hh_reverse")
        biasIhR = weights.getValue("bias_ih_reverse")[0]
        biasHhR = weights.getValue("bias_hh_reverse")[0]
        hidden0 = weights.getValue("hidden_weight_l0")
        bias0 = weights.getValue("hidden_bias_l0")[0]
        hidden1 = weights.getValue("hidden_weight_l1")
        bias1 = weights.getValue("hidden_bias_l1")[0]
        unk = charToId.getValue("<UNK>")
        bos = charToId.getValue("시")
        eos = charToId.getValue("끝")
    }

    fun pronounce(sentence: String): List<String> {
        if (sentence.isEmpty()) return emptyList()
        val ids = ArrayList<Int>(sentence.length)
        val result = ArrayList<String>(sentence.length)
        val poly = ArrayList<Int>()
        for ((index, ch) in sentence.withIndex()) {
            val text = ch.toString()
            ids.add(charToId[text] ?: unk)
            val readings = cedict[text]
            if (readings == null) result.add(text)
            else if (readings.size > 1) {
                poly.add(index)
                result.add("▁")
            } else result.add(readings[0])
        }
        if (poly.isNotEmpty()) {
            val withEnds = IntArray(ids.size + 2)
            withEnds[0] = bos
            for (index in ids.indices) withEnds[index + 1] = ids[index]
            withEnds[withEnds.lastIndex] = eos
            val chosen = predict(withEnds, IntArray(poly.size) { poly[it] + 1 })
            for (slot in poly.indices) result[poly[slot]] = idToClass.getValue(chosen[slot])
        }
        return result.map { it.replace("u:", "v") }
    }

    private fun predict(ids: IntArray, targets: IntArray): IntArray {
        var length = 0
        for (id in ids) if (id != 0) length += 1
        val forward = embed(ids)
        val backward = embed(reverseIds(ids, length))
        var fh = DoubleArray(weightHh[0].size)
        var fc = DoubleArray(fh.size)
        var bh = DoubleArray(fh.size)
        var bc = DoubleArray(fh.size)
        val fwOut = Array(ids.size) { DoubleArray(fh.size) }
        val bwOut = Array(ids.size) { DoubleArray(fh.size) }
        for (time in ids.indices) {
            val fState = lstm(forward[time], fh, fc, weightIh, weightHh, biasIh, biasHh)
            fh = fState.first
            fc = fState.second
            fwOut[time] = fh
            val bState = lstm(backward[time], bh, bc, weightIhR, weightHhR, biasIhR, biasHhR)
            bh = bState.first
            bc = bState.second
            bwOut[time] = bh
        }
        val bwAligned = reverseRows(bwOut, length)
        val preds = IntArray(targets.size)
        for ((index, target) in targets.withIndex()) {
            val joined = DoubleArray(fwOut[target].size + bwAligned[target].size)
            fwOut[target].copyInto(joined)
            bwAligned[target].copyInto(joined, fwOut[target].size)
            val hidden = relu(gemv(hidden0, bias0, joined))
            val logits = gemv(hidden1, bias1, hidden)
            var best = 0
            var bestValue = Double.NEGATIVE_INFINITY
            for (cls in logits.indices) {
                if (logits[cls] > bestValue) {
                    bestValue = logits[cls]
                    best = cls
                }
            }
            preds[index] = best
        }
        return preds
    }

    private fun embed(ids: IntArray): Array<DoubleArray> = Array(ids.size) { embeddings[ids[it]] }

    private fun reverseIds(ids: IntArray, length: Int): IntArray {
        val out = IntArray(ids.size)
        for (index in 0 until length) out[index] = ids[length - 1 - index]
        return out
    }

    private fun reverseRows(rows: Array<DoubleArray>, length: Int): Array<DoubleArray> {
        val out = Array(rows.size) { DoubleArray(rows[0].size) }
        for (index in 0 until length) out[index] = rows[length - 1 - index]
        return out
    }

    private fun lstm(
        input: DoubleArray,
        prevH: DoubleArray,
        prevC: DoubleArray,
        weightI: Array<DoubleArray>,
        weightH: Array<DoubleArray>,
        biasI: DoubleArray,
        biasH: DoubleArray,
    ): Pair<DoubleArray, DoubleArray> {
        val gates = DoubleArray(biasI.size)
        for (row in gates.indices) {
            var sum = biasI[row] + biasH[row]
            val inRow = weightI[row]
            val hRow = weightH[row]
            for (col in input.indices) sum += input[col] * inRow[col]
            for (col in prevH.indices) sum += prevH[col] * hRow[col]
            gates[row] = sum
        }
        val half = gates.size / 2
        val quarter = gates.size / 4
        val nextC = DoubleArray(quarter)
        val nextH = DoubleArray(quarter)
        for (index in 0 until quarter) {
            val inputGate = sigmoid(gates[index] )
            val forget = sigmoid(gates[quarter + index])
            val cell = tanh(gates[half + index])
            val output = sigmoid(gates[half + quarter + index])
            nextC[index] = forget * prevC[index] + inputGate * cell
            nextH[index] = output * tanh(nextC[index])
        }
        return nextH to nextC
    }

    private fun gemv(weight: Array<DoubleArray>, bias: DoubleArray, input: DoubleArray): DoubleArray {
        val out = DoubleArray(weight.size)
        for (row in weight.indices) {
            var sum = bias[row]
            val vector = weight[row]
            for (col in input.indices) sum += input[col] * vector[col]
            out[row] = sum
        }
        return out
    }

    private fun relu(values: DoubleArray): DoubleArray {
        for (index in values.indices) if (values[index] < 0.0) values[index] = 0.0
        return values
    }

    private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))
}
