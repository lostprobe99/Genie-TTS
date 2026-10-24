package com.genie.tts.g2p

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * WordPiece used by chinese-roberta-wwm-ext-large.
 * BertNormalizer, then BertPreTokenizer, then [CLS] and [SEP].
 */
class BertTokenizer(private val vocab: Map<String, Int>) {
    private val unk = vocab.getValue("[UNK]")
    private val cls = vocab.getValue("[CLS]")
    private val sep = vocab.getValue("[SEP]")

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>()
        ids.add(cls)
        for (piece in preTokenize(normalize(text))) ids.addAll(wordPiece(piece))
        ids.add(sep)
        return ids.toIntArray()
    }

    private fun normalize(text: String): String {
        val cleaned = StringBuilder()
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            if (code == 0 || code == 0xFFFD || isControl(code)) continue
            if (isWhitespace(code)) cleaned.append(' ') else cleaned.appendCodePoint(code)
        }
        val spaced = StringBuilder()
        val source = cleaned.toString()
        index = 0
        while (index < source.length) {
            val code = source.codePointAt(index)
            index += Character.charCount(code)
            if (isChinese(code)) {
                spaced.append(' ')
                spaced.appendCodePoint(code)
                spaced.append(' ')
            } else {
                spaced.appendCodePoint(code)
            }
        }
        return stripAccents(spaced.toString().lowercase(Locale.ROOT))
    }

    private fun preTokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (piece in text.split(' ')) {
            if (piece.isEmpty()) continue
            val current = StringBuilder()
            var index = 0
            while (index < piece.length) {
                val code = piece.codePointAt(index)
                index += Character.charCount(code)
                if (isPunctuation(code)) {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.clear()
                    }
                    out.add(String(Character.toChars(code)))
                } else {
                    current.appendCodePoint(code)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out
    }

    private fun wordPiece(token: String): List<Int> {
        val chars = ArrayList<String>()
        var index = 0
        while (index < token.length) {
            val code = token.codePointAt(index)
            chars.add(String(Character.toChars(code)))
            index += Character.charCount(code)
        }
        if (chars.size > 100) return listOf(unk)
        val ids = ArrayList<Int>()
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var found: Int? = null
            while (start < end) {
                val piece = chars.subList(start, end).joinToString("")
                val key = if (start > 0) "##$piece" else piece
                val id = vocab[key]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found == null) return listOf(unk)
            ids.add(found)
            start = end
        }
        return ids
    }

    private fun stripAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var index = 0
        while (index < decomposed.length) {
            val code = decomposed.codePointAt(index)
            index += Character.charCount(code)
            if (Character.getType(code) != Character.NON_SPACING_MARK.toInt()) out.appendCodePoint(code)
        }
        return out.toString()
    }

    private fun isControl(code: Int): Boolean {
        if (code == '\t'.code || code == '\n'.code || code == '\r'.code) return false
        return Character.getType(code) == Character.CONTROL.toInt()
    }

    private fun isWhitespace(code: Int): Boolean {
        if (code == ' '.code || code == '\t'.code || code == '\n'.code || code == '\r'.code) return true
        return code == 0x85 || code == 0xA0 || code == 0x1680 || code == 0x2028 || code == 0x2029 ||
            code == 0x202F || code == 0x205F || code == 0x3000 || code in 0x2000..0x200A
    }

    private fun isChinese(code: Int): Boolean {
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x20000..0x2A6DF ||
            code in 0x2A700..0x2B73F ||
            code in 0x2B740..0x2B81F ||
            code in 0x2B820..0x2CEAF ||
            code in 0xF900..0xFAFF ||
            code in 0x2F800..0x2FA1F
    }

    private fun isPunctuation(code: Int): Boolean {
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(code)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    companion object {
        fun load(reader: BufferedReader): BertTokenizer {
            val vocab = HashMap<String, Int>(24000)
            var index = 0
            while (true) {
                val line = reader.readLine() ?: break
                vocab[line] = index
                index++
            }
            return BertTokenizer(vocab)
        }

        fun load(stream: InputStream): BertTokenizer = stream.bufferedReader(Charsets.UTF_8).use { load(it) }

        fun load(file: File): BertTokenizer = file.bufferedReader(Charsets.UTF_8).use { load(it) }
    }
}
