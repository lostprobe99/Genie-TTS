package com.genie.tts.speech.g2p

internal sealed class SpeechSegment {
    class Chinese(val result: PhoneResult) : SpeechSegment()
    class English(val count: Int) : SpeechSegment()
}

/** Phone ids for one sentence, with Chinese and English spans kept separate for bert. */
internal class SpokenText(
    val ids: IntArray,
    val segments: List<SpeechSegment>,
)

/**
 * Chinese stays on [ChineseFrontend]. Letter runs go through [EnglishG2p].
 * The leading pause is added once, same as Inference.py / GetPhonesAndBert hybrid.
 */
internal class MixedSpeech(
    private val chinese: ChineseFrontend,
    private val english: EnglishG2p,
) {
    fun speak(userText: String): SpokenText {
        val chunks = LanguageSplit.split(userText)
        if (chunks.none { it is TextChunk.En }) {
            val result = chinese.infer(userText)
            return SpokenText(result.ids, listOf(SpeechSegment.Chinese(result)))
        }
        val ids = ArrayList<Int>()
        val segments = ArrayList<SpeechSegment>()
        var lead = true
        for (chunk in chunks) {
            when (chunk) {
                is TextChunk.Han -> {
                    val text = if (lead) "。${chunk.text}" else chunk.text
                    lead = false
                    val result = chinese.phones(text)
                    if (result.ids.isEmpty()) continue
                    result.ids.forEach { ids.add(it) }
                    segments.add(SpeechSegment.Chinese(result))
                }
                is TextChunk.En -> {
                    if (lead) {
                        val pause = chinese.phones("。")
                        lead = false
                        if (pause.ids.isNotEmpty()) {
                            pause.ids.forEach { ids.add(it) }
                            segments.add(SpeechSegment.Chinese(pause))
                        }
                    }
                    val spoken = english.pronounce(chunk.word, chunk.possessive)
                    if (spoken.isEmpty()) continue
                    spoken.forEach { ids.add(it) }
                    segments.add(SpeechSegment.English(spoken.size))
                }
            }
        }
        return SpokenText(ids.toIntArray(), segments)
    }
}
