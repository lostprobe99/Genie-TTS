package com.genie.tts.g2p

internal sealed class TextChunk {
    class Han(val text: String) : TextChunk()
    class En(val word: String, val possessive: Boolean) : TextChunk()
}

/** Cuts letter runs out of mixed text the way GetPhonesAndBert.split_language does. */
internal object LanguageSplit {
    fun split(text: String): List<TextChunk> {
        if (text.isEmpty()) return emptyList()
        val folded = foldLetters(text)
        val out = ArrayList<TextChunk>()
        var index = 0
        while (index < folded.length) {
            if (isLetter(folded[index])) {
                val start = index
                index = readWord(folded, index)
                var possessive = false
                if (isPossessive(folded, index)) {
                    possessive = true
                    index += 2
                }
                out.add(TextChunk.En(folded.substring(start, index - if (possessive) 2 else 0), possessive))
            } else {
                val start = index
                while (index < folded.length && !isLetter(folded[index])) index += 1
                out.add(TextChunk.Han(folded.substring(start, index)))
            }
        }
        return out
    }

    fun hasEnglish(text: String): Boolean = split(text).any { it is TextChunk.En }

    private fun readWord(text: String, start: Int): Int {
        var index = start
        while (index < text.length && isLetter(text[index])) index += 1
        while (index < text.length && text[index] == '-' && index + 1 < text.length && isLetter(text[index + 1])) {
            index += 1
            while (index < text.length && isLetter(text[index])) index += 1
        }
        return index
    }

    private fun isPossessive(text: String, index: Int): Boolean {
        if (index + 1 >= text.length) return false
        val mark = text[index]
        if (mark != '\'' && mark != '’') return false
        if (!text[index + 1].equals('s', ignoreCase = true)) return false
        return index + 2 == text.length || !isLetter(text[index + 2])
    }

    private fun foldLetters(text: String): String = buildString(text.length) {
        for (ch in text) {
            val code = ch.code
            if (code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A) append((code - 65248).toChar())
            else append(ch)
        }
    }

    private fun isLetter(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z'
}
