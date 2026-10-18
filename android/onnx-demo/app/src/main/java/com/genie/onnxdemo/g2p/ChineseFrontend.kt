package com.genie.onnxdemo.g2p

data class PhoneResult(
    val normalized: String,
    val phones: List<String>,
    val ids: IntArray,
    val word2ph: IntArray,
)

/**
 * On-device copy of ChineseG2P plus the leading period added by Core/Inference.py.
 * word2ph counts phones per character after erhua, before the symbol filter.
 */
class ChineseFrontend(files: G2pFiles) {
    private val normalizer = TextNormalizer(files)
    private val jieba = Jieba(files)
    private val model = G2pM(files)
    private val pinyin = Pinyin(files)
    private val sandhi = ToneSandhi(files, jieba, pinyin)
    private val opencpop = HashMap<String, String>()
    private val polyphonic = HashMap<String, List<String>>()
    private val symbols = HashMap<String, Int>()

    init {
        for (line in files.lines("opencpop.txt")) {
            val tab = line.indexOf('\t')
            if (tab > 0) opencpop[line.substring(0, tab)] = line.substring(tab + 1)
        }
        for (line in files.lines("polyphonic.txt")) {
            val tab = line.indexOf('\t')
            polyphonic[line.substring(0, tab)] = line.substring(tab + 1).split(' ')
        }
        files.lines("symbols.txt").forEachIndexed { index, symbol -> symbols[symbol] = index }
    }

    fun infer(userText: String): PhoneResult = phones("。$userText")

    fun phones(text: String): PhoneResult {
        val normalized = normalize(text)
        val raw = ArrayList<String>()
        val counts = ArrayList<Int>()
        for (segment in splitKeep(SPLIT, normalized)) {
            if (segment.isBlank()) continue
            val piece = phonesOf(ENG.replace(segment, ""))
            raw.addAll(piece.phones)
            piece.counts.forEach { counts.add(it) }
        }
        val kept = raw.filter { it in symbols }
        return PhoneResult(
            normalized,
            kept,
            kept.map { symbols.getValue(it) }.toIntArray(),
            counts.toIntArray(),
        )
    }

    private fun normalize(text: String): String {
        val joined = normalizer.normalize(text).joinToString("") { replacePunctuation(it) }
        return CONSECUTIVE.replace(joined) { it.groupValues[1] }
    }

    private fun replacePunctuation(input: String): String {
        var text = input.replace("...", "…")
        text = PUNCT.replace(text) { PUNCT_MAP.getValue(it.value) }
        return FILTER.replace(text, "")
    }

    private class Piece(val phones: List<String>, val counts: IntArray)

    private fun phonesOf(segment: String): Piece {
        if (segment.isEmpty()) return Piece(emptyList(), IntArray(0))
        val words = sandhi.preMerge(jieba.posCut(segment))
        val readings = model.pronounce(segment)
        val phones = ArrayList<String>()
        val counts = ArrayList<Int>()
        var cursor = 0
        for (token in words) {
            val end = cursor + token.word.length
            if (token.pos == "eng") {
                cursor = end
                continue
            }
            val spoken = correct(token.word, readings.subList(cursor, end.coerceAtMost(readings.size)))
            val initials = ArrayList<String>()
            val finals = ArrayList<String>()
            for (item in spoken) {
                if (item.isNotEmpty() && item[0].isLetter()) {
                    initials.add(pinyin.toInitials(item))
                    finals.add(pinyin.toFinalsTone3(item))
                } else {
                    initials.add(item)
                    finals.add(item)
                }
            }
            val tuned = sandhi.modify(token.word, token.pos, finals)
            val (erInitials, erFinals) = sandhi.erhua(initials, tuned, token.word, token.pos)
            for ((initial, final) in erInitials.zip(erFinals)) {
                if (initial == final) {
                    phones.add(initial)
                    counts.add(1)
                } else {
                    val pair = toOpencpop(initial, final) ?: continue
                    phones.addAll(pair)
                    counts.add(pair.size)
                }
            }
            cursor = end
        }
        return Piece(phones, counts.toIntArray())
    }

    private fun correct(word: String, current: List<String>): List<String> {
        polyphonic[word]?.let { return it }
        val out = current.toMutableList()
        for (index in word.indices) {
            if (index >= out.size) break
            polyphonic[word[index].toString()]?.let { out[index] = it[0] }
        }
        return out
    }

    private fun toOpencpop(initial: String, final: String): List<String>? {
        if (final.isEmpty()) return null
        val tone = final.last()
        val bare = final.dropLast(1)
        val key = if (initial.isNotEmpty()) {
            initial + (V_REP[bare] ?: bare)
        } else {
            when {
                bare in PINYIN_REP -> PINYIN_REP.getValue(bare)
                bare.isNotEmpty() && bare[0].toString() in SINGLE_REP -> {
                    SINGLE_REP.getValue(bare[0].toString()) + bare.substring(1)
                }
                else -> bare
            }
        }
        val mapped = opencpop[key] ?: return null
        val parts = mapped.split(' ')
        if (parts.size < 2) return null
        return listOf(parts[0], parts[1] + tone)
    }

    companion object {
        private val PUNCT_MAP = mapOf(
            "：" to ",", "；" to ",", "，" to ",", "。" to ".", "！" to "!",
            "？" to "?", "\n" to ".", "·" to ",", "、" to ",", "$" to ".",
            "/" to ",", "—" to "-", "~" to "…", "～" to "…",
        )
        private val PUNCT = Regex(PUNCT_MAP.keys.joinToString("|") { Regex.escape(it) })
        private val FILTER = Regex("""[^\u4e00-\u9fa5!?…,.\-]+""")
        private val SPLIT = Regex("""(?<=[!?…,.\-])\s*""")
        private val CONSECUTIVE = Regex("""([!?…,.\-])\1+""")
        private val ENG = Regex("""[a-zA-Z]+""")
        private val V_REP = mapOf("uei" to "ui", "iou" to "iu", "uen" to "un")
        private val PINYIN_REP = mapOf("ing" to "ying", "i" to "yi", "in" to "yin", "u" to "wu")
        private val SINGLE_REP = mapOf("v" to "yu", "e" to "e", "i" to "y", "u" to "w")
    }
}
