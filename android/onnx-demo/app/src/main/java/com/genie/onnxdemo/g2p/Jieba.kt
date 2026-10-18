package com.genie.onnxdemo.g2p

import kotlin.math.ln

data class PosToken(var word: String, var pos: String)

/**
 * jieba_fast segmentation used by the Chinese frontend.
 * Part-of-speech cuts follow the Python scorer. Search cuts follow the C scorer,
 * including its 12-candidate cap and tie break.
 */
class Jieba(files: G2pFiles) {
    private val freq = HashMap<String, Int>(400_000)
    private val tags = HashMap<String, String>(350_000)
    private val total: Int
    private val finalHmm: Hmm
    private val posHmm: Hmm

    init {
        var sum = 0
        for (line in files.lines("jieba_dict.txt")) {
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val word = parts[0]
            val count = parts[1].toInt()
            freq[word] = count
            sum += count
            if (parts.size >= 3) tags[word] = parts[2]
            for (end in 1..word.length) {
                val frag = word.substring(0, end)
                if (!freq.containsKey(frag)) freq[frag] = 0
            }
        }
        total = sum
        finalHmm = Hmm.load(files.lines("hmm_final.txt"), withChars = false)
        posHmm = Hmm.load(files.lines("hmm_pos.txt"), withChars = true)
    }

    fun posCut(sentence: String): List<PosToken> {
        if (sentence.isEmpty()) return emptyList()
        val out = ArrayList<PosToken>()
        for (block in splitKeep(HAN_INTERNAL, sentence)) {
            if (block.isEmpty()) continue
            if (HAN_INTERNAL.matches(block)) {
                out.addAll(posCutDag(block))
            } else {
                for (piece in splitKeep(SKIP_SPACE, block)) {
                    if (piece.isEmpty()) continue
                    if (SKIP_SPACE.matches(piece)) {
                        out.add(PosToken(piece, "x"))
                    } else {
                        for (ch in piece) {
                            val text = ch.toString()
                            val flag = when {
                                NUM.matches(text) -> "m"
                                ENG.matches(piece) -> "eng"
                                else -> "x"
                            }
                            out.add(PosToken(text, flag))
                        }
                    }
                }
            }
        }
        return out
    }

    fun cutForSearch(sentence: String): List<String> {
        val out = ArrayList<String>()
        for (word in cut(sentence)) {
            if (word.length > 2) {
                for (start in 0 until word.length - 1) {
                    val gram = word.substring(start, start + 2)
                    if ((freq[gram] ?: 0) != 0) out.add(gram)
                }
            }
            if (word.length > 3) {
                for (start in 0 until word.length - 2) {
                    val gram = word.substring(start, start + 3)
                    if ((freq[gram] ?: 0) != 0) out.add(gram)
                }
            }
            out.add(word)
        }
        return out
    }

    private fun cut(sentence: String): List<String> {
        val out = ArrayList<String>()
        for (block in splitKeep(HAN_DEFAULT, sentence)) {
            if (block.isEmpty()) continue
            if (HAN_DEFAULT.matches(block)) out.addAll(cutDag(block))
            else {
                for (piece in splitKeep(SKIP_SPACE, block)) {
                    if (piece.isEmpty()) continue
                    if (SKIP_SPACE.matches(piece)) out.add(piece)
                    else piece.forEach { out.add(it.toString()) }
                }
            }
        }
        return out
    }

    private fun cutDag(sentence: String): List<String> {
        val route = route(sentence, preferLongerOnTie = false, limit = 12)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var cursor = 0
        while (cursor < sentence.length) {
            val end = route[cursor] + 1
            if (end - cursor == 1) buf.append(sentence[cursor])
            else {
                flushSearch(buf, out)
                out.add(sentence.substring(cursor, end))
            }
            cursor = end
        }
        flushSearch(buf, out)
        return out
    }

    private fun flushSearch(buf: StringBuilder, out: MutableList<String>) {
        if (buf.isEmpty()) return
        val text = buf.toString()
        buf.setLength(0)
        when {
            text.length == 1 -> out.add(text)
            (freq[text] ?: 0) == 0 -> out.addAll(finalCut(text))
            else -> text.forEach { out.add(it.toString()) }
        }
    }

    private fun posCutDag(sentence: String): List<PosToken> {
        val route = route(sentence, preferLongerOnTie = true, limit = null)
        val out = ArrayList<PosToken>()
        val buf = StringBuilder()
        var cursor = 0
        while (cursor < sentence.length) {
            val end = route[cursor] + 1
            if (end - cursor == 1) buf.append(sentence[cursor])
            else {
                flushPos(buf, out)
                val word = sentence.substring(cursor, end)
                out.add(PosToken(word, tags[word] ?: "x"))
            }
            cursor = end
        }
        flushPos(buf, out)
        return out
    }

    private fun flushPos(buf: StringBuilder, out: MutableList<PosToken>) {
        if (buf.isEmpty()) return
        val text = buf.toString()
        buf.setLength(0)
        when {
            text.length == 1 -> out.add(PosToken(text, tags[text] ?: "x"))
            (freq[text] ?: 0) == 0 -> out.addAll(posDetail(text))
            else -> text.forEach { out.add(PosToken(it.toString(), tags[it.toString()] ?: "x")) }
        }
    }

    private fun posDetail(sentence: String): List<PosToken> {
        val out = ArrayList<PosToken>()
        for (block in splitKeep(HAN_DETAIL, sentence)) {
            if (block.isEmpty()) continue
            if (HAN_DETAIL.matches(block)) out.addAll(cutPosHmm(block))
            else {
                for (piece in splitKeep(SKIP_DETAIL, block)) {
                    if (piece.isEmpty()) continue
                    val flag = when {
                        NUM.matches(piece) -> "m"
                        ENG.matches(piece) -> "eng"
                        else -> "x"
                    }
                    out.add(PosToken(piece, flag))
                }
            }
        }
        return out
    }

    private fun route(sentence: String, preferLongerOnTie: Boolean, limit: Int?): IntArray {
        val n = sentence.length
        val dag = ArrayList<IntArray>(n)
        for (start in 0 until n) {
            val ends = ArrayList<Int>()
            var index = start
            var frag = sentence.substring(start, start + 1)
            while (index < n && freq.containsKey(frag) && (limit == null || ends.size < limit)) {
                if (freq.getValue(frag) != 0) ends.add(index)
                index += 1
                if (index >= n) break
                frag = sentence.substring(start, index + 1)
            }
            if (ends.isEmpty()) ends.add(start)
            dag.add(ends.toIntArray())
        }
        val score = DoubleArray(n + 1)
        val end = IntArray(n + 1)
        val logTotal = ln(total.toDouble())
        for (index in n - 1 downTo 0) {
            var best = Double.NEGATIVE_INFINITY
            var bestEnd = 0
            for (candidate in dag[index]) {
                val word = sentence.substring(index, candidate + 1)
                var count = freq[word] ?: 1
                if (count == 0) count = 1
                val value = ln(count.toDouble()) - logTotal + score[candidate + 1]
                val better = if (preferLongerOnTie) value >= best else value > best
                if (better) {
                    best = value
                    bestEnd = candidate
                }
            }
            score[index] = best
            end[index] = bestEnd
        }
        return end
    }

    private fun finalCut(sentence: String): List<String> {
        val out = ArrayList<String>()
        for (block in splitKeep(HAN_DETAIL, sentence)) {
            if (block.isEmpty()) continue
            if (HAN_DETAIL.matches(block)) out.addAll(cutBmes(finalHmm.viterbiBmes(block), block))
            else {
                for (piece in splitKeep(FINAL_SKIP, block)) {
                    if (piece.isNotEmpty()) out.add(piece)
                }
            }
        }
        return out
    }

    private fun cutPosHmm(sentence: String): List<PosToken> {
        val states = posHmm.viterbiPos(sentence)
        val out = ArrayList<PosToken>()
        var begin = 0
        var next = 0
        for (index in sentence.indices) {
            val state = states[index]
            val kind = state.substringBefore('/')
            val tag = state.substringAfter('/')
            when (kind) {
                "B" -> begin = index
                "E" -> {
                    out.add(PosToken(sentence.substring(begin, index + 1), tag))
                    next = index + 1
                }
                "S" -> {
                    out.add(PosToken(sentence.substring(index, index + 1), tag))
                    next = index + 1
                }
            }
        }
        if (next < sentence.length) {
            val tag = states[next].substringAfter('/')
            out.add(PosToken(sentence.substring(next), tag))
        }
        return out
    }

    private fun cutBmes(states: String, sentence: String): List<String> {
        val out = ArrayList<String>()
        var begin = 0
        var next = 0
        for (index in sentence.indices) {
            when (states[index]) {
                'B' -> begin = index
                'E' -> {
                    out.add(sentence.substring(begin, index + 1))
                    next = index + 1
                }
                'S' -> {
                    out.add(sentence.substring(index, index + 1))
                    next = index + 1
                }
            }
        }
        if (next < sentence.length) out.add(sentence.substring(next))
        return out
    }

    private class Hmm(
        val start: Map<String, Double>,
        val trans: Map<String, Map<String, Double>>,
        val emit: Map<String, Map<Char, Double>>,
        val chars: Map<Char, List<String>>,
    ) {
        fun viterbiBmes(sentence: String): String {
            val n = sentence.length
            val order = charArrayOf('B', 'M', 'E', 'S')
            fun at(ch: Char): Int = when (ch) {
                'B' -> 0
                'M' -> 1
                'E' -> 2
                else -> 3
            }
            val score = Array(n) { DoubleArray(4) }
            val prev = Array(n) { CharArray(4) }
            for (state in order) {
                score[0][at(state)] = (start[state.toString()] ?: MIN_FLOAT) + emitProb(state.toString(), sentence[0])
                prev[0][at(state)] = state
            }
            for (time in 1 until n) {
                for (state in order) {
                    val added = emitProb(state.toString(), sentence[time])
                    var best = MIN_FLOAT
                    var bestFrom = '\u0000'
                    for (from in PREV_BMES.getValue(state)) {
                        val jump = trans[from.toString()]?.get(state.toString()) ?: MIN_FLOAT
                        val value = score[time - 1][at(from)] + jump + added
                        if (value > best) {
                            best = value
                            bestFrom = from
                        }
                    }
                    if (bestFrom == '\u0000') {
                        for (from in PREV_BMES.getValue(state)) {
                            if (from > bestFrom) bestFrom = from
                        }
                    }
                    score[time][at(state)] = best
                    prev[time][at(state)] = bestFrom
                }
            }
            var chosen = 'E'
            if (score[n - 1][at('S')] > score[n - 1][at('E')]) chosen = 'S'
            val route = CharArray(n)
            for (time in n - 1 downTo 0) {
                route[time] = chosen
                chosen = prev[time][at(chosen)]
            }
            return String(route)
        }

        fun viterbiPos(sentence: String): List<String> {
            val n = sentence.length
            val score = Array(n) { HashMap<String, Double>() }
            val prev = Array(n) { HashMap<String, String>() }
            val all = trans.keys
            val firstStates = chars[sentence[0]] ?: all.toList()
            for (state in firstStates) {
                score[0][state] = (start[state] ?: MIN_FLOAT) + emitProb(state, sentence[0])
                prev[0][state] = ""
            }
            for (time in 1 until n) {
                val previous = prev[time - 1].keys.filter { trans[it]?.isNotEmpty() == true }
                val expected = LinkedHashSet<String>()
                for (state in previous) trans[state]?.keys?.let { expected.addAll(it) }
                val observed = chars[sentence[time]]
                var pool = if (observed == null) all.toList() else observed.filter { it in expected }
                if (pool.isEmpty()) pool = if (expected.isNotEmpty()) expected.toList() else all.toList()
                for (state in pool) {
                    var best = Double.NEGATIVE_INFINITY
                    var fromState = ""
                    var seen = false
                    val added = emitProb(state, sentence[time])
                    for (from in previous) {
                        val value = (score[time - 1][from] ?: MIN_INF) +
                            (trans[from]?.get(state) ?: MIN_INF) + added
                        if (!seen || value > best || (value == best && from > fromState)) {
                            best = value
                            fromState = from
                            seen = true
                        }
                    }
                    score[time][state] = best
                    prev[time][state] = fromState
                }
            }
            var best = Double.NEGATIVE_INFINITY
            var chosen = ""
            var seen = false
            for ((state, value) in score[n - 1]) {
                if (!seen || value > best || (value == best && state > chosen)) {
                    best = value
                    chosen = state
                    seen = true
                }
            }
            val route = ArrayList<String>(n)
            repeat(n) { route.add("") }
            for (time in n - 1 downTo 0) {
                route[time] = chosen
                chosen = prev[time][chosen].orEmpty()
            }
            return route
        }

        private fun emitProb(state: String, char: Char): Double = emit[state]?.get(char) ?: MIN_FLOAT

        companion object {
            private val PREV_BMES = mapOf(
                'B' to charArrayOf('E', 'S'),
                'M' to charArrayOf('M', 'B'),
                'S' to charArrayOf('S', 'E'),
                'E' to charArrayOf('B', 'M'),
            )

            fun load(lines: List<String>, withChars: Boolean): Hmm {
                val start = HashMap<String, Double>()
                val trans = HashMap<String, HashMap<String, Double>>()
                val emit = HashMap<String, HashMap<Char, Double>>()
                val chars = HashMap<Char, List<String>>()
                var section = ""
                for (line in lines) {
                    if (line == "START" || line == "TRANS" || line == "EMIT" || line == "CHARS") {
                        section = line
                        continue
                    }
                    val parts = line.split('\t')
                    when (section) {
                        "START" -> start[parts[0]] = parts[1].toDouble()
                        "TRANS" -> trans.getOrPut(parts[0]) { HashMap() }[parts[1]] = parts[2].toDouble()
                        "EMIT" -> emit.getOrPut(parts[0]) { HashMap() }[parts[1][0]] = parts[2].toDouble()
                        "CHARS" -> if (withChars && parts[0].isNotEmpty()) {
                            chars[parts[0][0]] = parts[1].split(',')
                        }
                    }
                }
                return Hmm(start, trans, emit, chars)
            }
        }
    }

    companion object {
        private const val MIN_FLOAT = -3.14e100
        private const val MIN_INF = Double.NEGATIVE_INFINITY
        private val HAN_DEFAULT = Regex("([\\u4E00-\\u9FD5a-zA-Z0-9+#&._%]+)")
        private val HAN_INTERNAL = Regex("([\\u4E00-\\u9FD5a-zA-Z0-9+#&._]+)")
        private val HAN_DETAIL = Regex("([\\u4E00-\\u9FD5]+)")
        private val SKIP_SPACE = Regex("(\\r\\n|(?U)\\s)")
        private val SKIP_DETAIL = Regex("([.0-9]+|[a-zA-Z0-9]+)")
        private val FINAL_SKIP = Regex("([a-zA-Z0-9]+(?:\\.\\d+)?%?)")
        private val NUM = Regex("[.0-9]+")
        private val ENG = Regex("[a-zA-Z0-9]+")
    }
}

internal fun splitKeep(regex: Regex, text: String): List<String> {
    val out = ArrayList<String>()
    var last = 0
    for (match in regex.findAll(text)) {
        out.add(text.substring(last, match.range.first))
        out.add(match.value)
        last = match.range.last + 1
    }
    out.add(text.substring(last))
    return out
}
