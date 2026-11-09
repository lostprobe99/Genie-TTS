package com.genie.tts.speech.g2p

import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Unigram/bigram word segmentation from Genie EnglishG2P WordSegment.py.
 * Used when a glued English token is not in the CMU dictionary.
 */
class WordSegment(files: G2pFiles) {
    private val unigrams = HashMap<String, Double>()
    private val bigrams = HashMap<String, Double>()
    private val total = TOTAL

    init {
        files.forEachLine("wordseg_unigrams.txt") { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val count = line.substring(tab + 1)
            if (count.isEmpty()) return@forEachLine
            unigrams[line.substring(0, tab)] = count.toDouble()
        }
        files.forEachLine("wordseg_bigrams.txt") { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val count = line.substring(tab + 1)
            if (count.isEmpty()) return@forEachLine
            bigrams[line.substring(0, tab)] = count.toDouble()
        }
    }

    fun segment(text: String): List<String> {
        val clean = clean(text)
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= CHUNK) {
            return search(clean, "<s>").second
        }
        val out = ArrayList<String>()
        var prefix = ""
        var offset = 0
        while (offset < clean.length) {
            val end = min(offset + CHUNK, clean.length)
            val chunk = clean.substring(offset, end)
            val words = search(prefix + chunk, "<s>").second
            if (words.size > 5) {
                prefix = words.takeLast(5).joinToString("")
                out.addAll(words.dropLast(5))
            } else {
                prefix = words.joinToString("")
            }
            offset = end
        }
        out.addAll(search(prefix, "<s>").second)
        return out
    }

    private fun score(word: String, previous: String?): Double {
        if (previous == null) {
            val count = unigrams[word]
            if (count != null) return count / total
            return 10.0 / (total * 10.0.pow(word.length))
        }
        val bigram = "$previous $word"
        val bigramCount = bigrams[bigram]
        if (bigramCount != null && previous in unigrams) {
            return bigramCount / total / score(previous, null)
        }
        return score(word, null)
    }

    private fun search(text: String, previous: String): Pair<Double, List<String>> {
        if (text.isEmpty()) return 0.0 to emptyList()
        val memo = HashMap<Pair<String, String>, Pair<Double, List<String>>>()
        fun walk(rest: String, prev: String): Pair<Double, List<String>> {
            if (rest.isEmpty()) return 0.0 to emptyList()
            var bestScore = Double.NEGATIVE_INFINITY
            var bestWords: List<String> = emptyList()
            val limit = min(rest.length, LIMIT)
            for (pos in 1..limit) {
                val prefix = rest.substring(0, pos)
                val suffix = rest.substring(pos)
                val prefixScore = log10(score(prefix, prev))
                val key = suffix to prefix
                val suffixResult = memo.getOrPut(key) { walk(suffix, prefix) }
                val totalScore = prefixScore + suffixResult.first
                if (totalScore > bestScore) {
                    bestScore = totalScore
                    bestWords = listOf(prefix) + suffixResult.second
                }
            }
            return bestScore to bestWords
        }
        return walk(text, previous)
    }

    companion object {
        private val ALPHABET = ('a'..'z').toSet() + ('0'..'9').toSet()
        private const val TOTAL = 1_024_908_267_229.0
        private const val LIMIT = 24
        private const val CHUNK = 250

        private fun clean(text: String): String = buildString(text.length) {
            for (ch in text.lowercase()) {
                if (ch in ALPHABET) append(ch)
            }
        }
    }
}
