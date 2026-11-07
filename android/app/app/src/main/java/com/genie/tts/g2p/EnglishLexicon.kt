package com.genie.tts.g2p

/**
 * First-pronunciation CMU dictionary. Unknown words produce no phones.
 * Possessive and hyphen rules follow EnglishG2P._query_word, without the neural guesser.
 */
class EnglishLexicon(files: G2pFiles) {
    private val symbolAt: List<String>
    private val dict = HashMap<String, IntArray>()

    init {
        val symbols = HashMap<String, Int>()
        val names = ArrayList<String>()
        files.lines("symbols.txt").forEachIndexed { index, symbol ->
            symbols[symbol] = index
            names.add(symbol)
        }
        symbolAt = names
        files.forEachLine("cmudict.txt") { line ->
            if (line.startsWith("#")) return@forEachLine
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val phones = line.substring(tab + 1).split(' ')
            val ids = IntArray(phones.size)
            for (index in phones.indices) {
                ids[index] = symbols[phones[index]] ?: return@forEachLine
            }
            dict[line.substring(0, tab)] = ids
        }
    }

    fun pronounce(word: String, possessive: Boolean = false): IntArray {
        val spoken = lookup(word.lowercase()) ?: return IntArray(0)
        if (!possessive || spoken.isEmpty()) return spoken
        val last = symbolAt[spoken.last()]
        val ending = when (last) {
            "S", "Z", "SH", "ZH", "CH", "JH" -> listOf("AH0", "Z")
            "P", "T", "K", "F", "TH" -> listOf("S")
            else -> listOf("Z")
        }
        val extra = ending.map { phone -> symbolAt.indexOf(phone) }
        if (extra.any { it < 0 }) return spoken
        return IntArray(spoken.size + extra.size) { index ->
            if (index < spoken.size) spoken[index] else extra[index - spoken.size]
        }
    }

    private fun lookup(word: String): IntArray? {
        dict[word]?.let { return it }
        if ('-' !in word) return null
        val parts = word.split('-').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val pieces = parts.map { dict[it] ?: return null }
        val total = pieces.sumOf { it.size }
        val joined = IntArray(total)
        var cursor = 0
        for (piece in pieces) {
            piece.copyInto(joined, cursor)
            cursor += piece.size
        }
        return joined
    }
}
