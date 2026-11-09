package com.genie.tts.speech.g2p

/**
 * On-device English G2P aligned with EnglishG2P._query_word:
 * CMU → title-case name dict → possessive → hyphen → WordSegment → neural predict.
 * Homographs keep master's table; without POS the non-matching reading is used.
 */
class EnglishG2p(files: G2pFiles) {
    private val symbolAt: List<String>
    private val symbolId = HashMap<String, Int>()
    private val cmu = HashMap<String, List<String>>()
    private val names = HashMap<String, List<String>>()
    private val homographs = HashMap<String, Homograph>()
    private val segmenter = WordSegment(files)
    private val neural = EnglishNeuralG2p(files)

    init {
        val namesList = ArrayList<String>()
        files.lines("symbols.txt").forEachIndexed { index, symbol ->
            symbolId[symbol] = index
            namesList.add(symbol)
        }
        symbolAt = namesList
        loadDict(files, "cmudict.txt", cmu)
        loadDict(files, "engdict_hot.txt", cmu)
        loadDict(files, "namedict.txt", names)
        for (word in DROPPED) cmu.remove(word)
        homographs["read"] = Homograph(listOf("R", "EH1", "D"), listOf("R", "IY1", "D"), "VBD")
        homographs["complex"] = Homograph(
            listOf("K", "AH0", "M", "P", "L", "EH1", "K", "S"),
            listOf("K", "AA1", "M", "P", "L", "EH0", "K", "S"),
            "JJ",
        )
        homographs["lead"] = Homograph(listOf("L", "IY1", "D"), listOf("L", "EH1", "D"), "NN")
        homographs["presents"] = Homograph(
            listOf("P", "R", "IY0", "Z", "EH1", "N", "T", "S"),
            listOf("P", "R", "EH1", "Z", "AH0", "N", "T", "S"),
            "VBZ",
        )
    }

    fun pronounce(word: String, possessive: Boolean = false): IntArray {
        val phones = queryWord(word).toMutableList()
        if (possessive && phones.isNotEmpty()) {
            phones += possessiveEnding(phones.last())
        }
        return toIds(phones)
    }

    /** True when [word] has a CMU first pronunciation (after hot words and dropped keys). */
    fun inDictionary(word: String): Boolean = word.lowercase() in cmu

    private fun queryWord(original: String): List<String> {
        val word = original.lowercase()
        val homograph = homographs[word]
        if (homograph != null) {
            // POS tagger is not shipped; use the non-matching reading as the default.
            return homograph.alt
        }
        cmu[word]?.let {
            if (original == "A") return listOf("AH0")
            return it
        }
        if (isTitleCase(original) && word in names) {
            return names.getValue(word)
        }
        if (word.endsWith("'s") && word.length > 2) {
            val base = queryWord(original.dropLast(2))
            if (base.isNotEmpty()) return base + possessiveEnding(base.last())
        }
        if ('-' in word && word.length > 1) {
            val parts = word.split('-').filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val joined = parts.flatMap { queryWord(it) }
                if (joined.isNotEmpty()) return joined
            }
        }
        val segments = segmenter.segment(word)
        if (segments.size > 1 && segments.joinToString("") == word) {
            val joined = segments.flatMap { queryWord(it) }
            if (joined.isNotEmpty()) return joined
        }
        return cleanPredicted(neural.predict(original))
    }

    private fun cleanPredicted(phones: List<String>): List<String> {
        val undesired = setOf(" ", "<pad>", "UW", "</s>", "<s>")
        return phones.mapNotNull { phone ->
            when {
                phone in undesired -> null
                phone == "<unk>" -> "UNK"
                phone == "'" -> "-"
                else -> phone
            }
        }
    }

    private fun possessiveEnding(last: String): List<String> = when (last) {
        "S", "Z", "SH", "ZH", "CH", "JH" -> listOf("AH0", "Z")
        "P", "T", "K", "F", "TH" -> listOf("S")
        else -> listOf("Z")
    }

    private fun toIds(phones: List<String>): IntArray {
        if (phones.isEmpty()) return IntArray(0)
        val ids = IntArray(phones.size)
        for (index in phones.indices) {
            ids[index] = symbolId[phones[index]] ?: return IntArray(0)
        }
        return ids
    }

    private fun loadDict(files: G2pFiles, name: String, target: HashMap<String, List<String>>) {
        files.forEachLine(name) { line ->
            if (line.startsWith("#")) return@forEachLine
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val phones = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            if (phones.isEmpty()) return@forEachLine
            target[line.substring(0, tab).lowercase()] = phones
        }
    }

    private data class Homograph(val match: List<String>, val alt: List<String>, val pos: String)

    companion object {
        private val DROPPED = listOf("ae", "ai", "ar", "ios", "hud", "os")

        /** Matches Python str.istitle() for namedict lookups. */
        private fun isTitleCase(text: String): Boolean {
            var seenCased = false
            var previousCased = false
            for (ch in text) {
                if (!ch.isLetter()) {
                    previousCased = false
                    continue
                }
                if (ch.isUpperCase()) {
                    if (previousCased) return false
                    seenCased = true
                    previousCased = true
                } else {
                    if (!previousCased) return false
                    seenCased = true
                    previousCased = true
                }
            }
            return seenCased
        }
    }
}
