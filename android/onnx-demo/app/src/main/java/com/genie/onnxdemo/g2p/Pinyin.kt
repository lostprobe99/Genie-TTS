package com.genie.onnxdemo.g2p

/** Pinyin lookup and initial/final split used by tone sandhi and the phone map. */
class Pinyin(files: G2pFiles) {
    private val chars = HashMap<String, String>(50_000)
    private val phrases = HashMap<String, List<String>>(50_000)
    private val prefixes = HashSet<String>(150_000)
    private val singleSymbol = HashMap<String, String>()
    private val multiSymbol = ArrayList<Pair<String, String>>()

    init {
        for (line in files.lines("pinyin_char.txt")) {
            val tab = line.indexOf('\t')
            chars[line.substring(0, tab)] = line.substring(tab + 1)
        }
        for (line in files.lines("phrases.txt")) {
            val tab = line.indexOf('\t')
            val word = line.substring(0, tab)
            phrases[word] = line.substring(tab + 1).split(' ')
            for (end in 1..word.length) prefixes.add(word.substring(0, end))
        }
        for (line in files.lines("phonetic.txt")) {
            val tab = line.indexOf('\t')
            val symbol = line.substring(0, tab)
            val replacement = line.substring(tab + 1)
            if (symbol.length == 1) singleSymbol[symbol] = replacement
            else multiSymbol.add(symbol to replacement)
        }
    }

    private fun simpleSeg(text: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var han = false
        var started = false
        var index = 0
        while (index < text.length) {
            val code = Character.codePointAt(text, index)
            val count = Character.charCount(code)
            val current = isHanCode(code)
            if (!started || current == han) buf.appendCodePoint(code)
            else {
                out.add(buf.toString())
                buf.setLength(0)
                buf.appendCodePoint(code)
            }
            han = current
            started = true
            index += count
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    fun finalsTone3(text: String): List<String> {
        val out = ArrayList<String>()
        for (piece in simpleSeg(text)) {
            if (!isHans(piece)) {
                if (piece.isNotEmpty()) out.add(piece)
                continue
            }
            for (token in forwardMax(piece)) {
                for (reading in readings(token)) out.add(toFinalsTone3(reading))
            }
        }
        return out
    }

    fun toInitials(pinyin: String): String = initialsOf(pinyin, strict = true)

    fun toFinalsTone3(pinyin: String): String {
        val trimmed = pinyin.replace("5", "")
        var finals = toFinals(trimmed)
        if (finals.isEmpty()) return finals
        val numbers = Regex("\\d").findAll(replaceSymbolToNumber(trimmed)).map { it.value }.toList()
        finals += if (numbers.isEmpty()) "5" else numbers[0]
        return finals
    }

    private fun readings(token: String): List<String> {
        phrases[token]?.let { return it }
        return token.map { ch -> chars[ch.toString()] ?: ch.toString() }
    }

    private fun forwardMax(text: String): List<String> {
        val out = ArrayList<String>()
        var remain = text
        while (remain.isNotEmpty()) {
            var matched = ""
            var lastValid = ""
            var lastIndex = 0
            var broken = false
            for (index in remain.indices) {
                val word = remain.substring(0, index + 1)
                if (word in prefixes) {
                    matched = word
                    lastValid = word
                    lastIndex = index + 1
                } else {
                    if (lastValid.isNotEmpty()) {
                        out.add(lastValid)
                        remain = remain.substring(lastIndex)
                    } else if (matched.isNotEmpty()) {
                        out.add(matched)
                        remain = remain.substring(matched.length)
                    } else {
                        out.add(remain.substring(0, 1))
                        remain = remain.substring(1)
                    }
                    broken = true
                    break
                }
            }
            if (!broken) {
                if (lastValid.isNotEmpty()) {
                    out.add(lastValid)
                    remain = remain.substring(lastIndex)
                } else {
                    out.add(remain)
                    break
                }
            }
        }
        return out
    }

    private fun toFinals(pinyin: String): String {
        val bare = replaceSymbolToNumber(pinyin).replace(Regex("\\d"), "").replace("v", "ü")
        return finalsOf(bare).replace("ü", "v")
    }

    private fun finalsOf(pinyin: String): String {
        val converted = convertFinals(pinyin)
        val strictInitial = initialsOf(converted, strict = true)
        var finals = converted.substring(strictInitial.length)
        if (finals !in FINALS) {
            val loose = initialsOf(converted, strict = false)
            finals = converted.substring(loose.length)
            if (finals in FINALS) return finals
            return ""
        }
        return finals
    }

    private fun initialsOf(pinyin: String, strict: Boolean): String {
        val table = if (strict) INITIALS else INITIALS_LOOSE
        for (initial in table) if (pinyin.startsWith(initial)) return initial
        return ""
    }

    private fun replaceSymbolToNumber(pinyin: String): String {
        val out = StringBuilder()
        for (ch in pinyin) out.append(singleSymbol[ch.toString()] ?: ch.toString())
        var value = out.toString()
        for ((symbol, replacement) in multiSymbol) value = value.replace(symbol, replacement)
        return value
    }

    private fun convertFinals(pinyin: String): String {
        var value = convertZero(pinyin)
        value = convertUv(value)
        value = convertPair(value, IU_MAP)
        value = convertPair(value, UI_MAP)
        value = convertPair(value, UN_MAP)
        return value
    }

    private fun convertZero(pinyin: String): String {
        var value = pinyin
        val raw = pinyin
        if (raw.startsWith("y")) {
            val rest = value.substring(1)
            val first = rest.firstOrNull()
            value = when {
                first != null && first in U_TONES -> UV_MAP.getValue(first.toString()) + value.substring(2)
                first != null && first in I_TONES -> rest
                else -> "i$rest"
            }
        }
        if (raw.startsWith("w")) {
            val rest = value.substring(1)
            val first = rest.firstOrNull()
            value = if (first != null && first in U_TONES) value.substring(1) else "u${value.substring(1)}"
        }
        return if (value in FINALS) value else raw
    }

    private fun convertUv(pinyin: String): String {
        val match = Regex("^(j|q|x)([uūúǔù])(.*)$").matchEntire(pinyin) ?: return pinyin
        return match.groupValues[1] + UV_MAP.getValue(match.groupValues[2]) + match.groupValues[3]
    }

    private fun convertPair(pinyin: String, table: Map<String, String>): String {
        val alternation = table.keys.joinToString("|") { Regex.escape(it) }
        val match = Regex("^([a-z]+)($alternation)$").matchEntire(pinyin) ?: return pinyin
        return match.groupValues[1] + table.getValue(match.groupValues[2])
    }

    companion object {
        private val INITIALS = listOf(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "zh", "ch", "sh", "r", "z", "c", "s",
        )
        private val INITIALS_LOOSE = INITIALS + listOf("y", "w")
        private val FINALS = setOf(
            "i", "u", "ü", "a", "ia", "ua", "o", "uo", "e", "ie", "üe", "ai", "uai",
            "ei", "uei", "ao", "iao", "ou", "iou", "an", "ian", "uan", "üan", "en",
            "in", "uen", "ün", "ang", "iang", "uang", "eng", "ing", "ueng", "ong",
            "iong", "er", "ê",
        )
        private val UV_MAP = mapOf("u" to "ü", "ū" to "ǖ", "ú" to "ǘ", "ǔ" to "ǚ", "ù" to "ǜ")
        private val U_TONES = UV_MAP.keys.map { it[0] }.toSet()
        private val I_TONES = setOf('i', 'ī', 'í', 'ǐ', 'ì')
        private val IU_MAP = mapOf("iu" to "iou", "iū" to "ioū", "iú" to "ioú", "iǔ" to "ioǔ", "iù" to "ioù")
        private val UI_MAP = mapOf("ui" to "uei", "uī" to "ueī", "uí" to "ueí", "uǐ" to "ueǐ", "uì" to "ueì")
        private val UN_MAP = mapOf("un" to "uen", "ūn" to "ūen", "ún" to "úen", "ǔn" to "ǔen", "ùn" to "ùen")
    }
}

internal fun isHans(text: String): Boolean {
    if (text.isEmpty()) return false
    var index = 0
    while (index < text.length) {
        val code = Character.codePointAt(text, index)
        if (!isHanCode(code)) return false
        index += Character.charCount(code)
    }
    return true
}

private fun isHanCode(code: Int): Boolean {
    return code == 0x3007 || code == 0xFA18 || code == 0x2D016 || code == 0x30EDD || code == 0x30EDE ||
        code in 0xE815..0xE864 ||
        code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF ||
        code in 0x20000..0x2A6DF ||
        code in 0x2A703..0x2B73F ||
        code in 0x2B740..0x2B81D ||
        code in 0x2B825..0x2BF6E ||
        code in 0x2C029..0x2CE93 ||
        code in 0x2D11B..0x2EBD9 ||
        code in 0x2F80A..0x2FA1F ||
        code in 0x30000..0x3134A ||
        code in 0x300F7..0x31288 ||
        code in 0x31350..0x32389
}
