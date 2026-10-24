package com.genie.tts

/**
 * 与 `genie_tts.Utils.TextSplitter` 同一套规则。
 * 有效长度跳过标点：ASCII 记 1，其余记 2。
 * 句号、问号、叹号、省略号在有效长度达到 [minLen] 时收成一句，更短的并进后面。
 * 逗号、顿号、分号、冒号和引号平时不断，有效长度达到 [maxLen] 才切开。
 * 换行先删掉，不当作断句。
 */
class TextSplitter(
    private val maxLen: Int = 40,
    private val minLen: Int = 5,
) {
    private val endChars = setOf("。", "！", "？", "…", "!", "?", ".")
    private val allPuncts = endChars + setOf(
        "，", "、", "；", "：", "——",
        ",", ";", ":",
        "“", "”", "‘", "’", "\"", "'",
    )
    private val pattern = Regex(
        "((?:" + allPuncts.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")+)",
    )

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val flattened = text.replace("\n", "")
        val sentences = ArrayList<String>()
        var buffer = ""
        for (segment in splitKeepingDelimiters(flattened)) {
            if (segment.isEmpty()) continue
            val punct = allPuncts.contains(segment.substring(0, 1))
            if (punct) {
                buffer += segment
                val length = effectiveLen(buffer)
                if (isTerminator(segment)) {
                    if (length >= minLen) {
                        sentences.add(buffer.trim())
                        buffer = ""
                    }
                } else if (length >= maxLen) {
                    sentences.add(buffer.trim())
                    buffer = ""
                }
            } else {
                buffer += segment
            }
        }
        flush(sentences, buffer)
        return sentences
    }

    private fun effectiveLen(text: String): Int {
        var length = 0
        for (char in text) {
            if (allPuncts.contains(char.toString())) continue
            length += if (char.code < 128) 1 else 2
        }
        return length
    }

    private fun isTerminator(block: String): Boolean {
        return block.any { endChars.contains(it.toString()) }
    }

    private fun flush(sentences: MutableList<String>, buffer: String) {
        val candidate = buffer.trim()
        if (candidate.isEmpty()) return
        if (effectiveLen(candidate) > 0) {
            sentences.add(candidate)
        } else if (sentences.isNotEmpty()) {
            sentences[sentences.lastIndex] = sentences.last() + candidate
        }
    }

    private fun splitKeepingDelimiters(text: String): List<String> {
        val parts = ArrayList<String>()
        var cursor = 0
        for (match in pattern.findAll(text)) {
            parts.add(text.substring(cursor, match.range.first))
            parts.add(match.value)
            cursor = match.range.last + 1
        }
        parts.add(text.substring(cursor))
        return parts
    }
}
