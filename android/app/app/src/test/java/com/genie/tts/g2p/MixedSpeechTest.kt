package com.genie.tts.g2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MixedSpeechTest {
    @Test
    fun splitsWordsHyphensAndPossessives() {
        val chunks = LanguageSplit.split("我喜欢 Apple's state-of-the-art。")
        assertEquals(5, chunks.size)
        assertEquals("我喜欢 ", (chunks[0] as TextChunk.Han).text)
        val owned = chunks[1] as TextChunk.En
        assertEquals("Apple", owned.word)
        assertTrue(owned.possessive)
        assertEquals(" ", (chunks[2] as TextChunk.Han).text)
        val hyphen = chunks[3] as TextChunk.En
        assertEquals("state-of-the-art", hyphen.word)
        assertEquals("。", (chunks[4] as TextChunk.Han).text)
        assertEquals("Apple", (LanguageSplit.split("Ａpple").single() as TextChunk.En).word)
    }

    @Test
    fun lexiconUsesFirstPronunciation() {
        val lexicon = EnglishLexicon(G2pFiles.fromDirectory(assets()))
        assertArrayEquals(ids("AE1", "P", "AH0", "L"), lexicon.pronounce("Apple"))
        assertArrayEquals(ids("AE1", "P", "AH0", "L", "Z"), lexicon.pronounce("Apple", possessive = true))
        assertArrayEquals(
            ids("S", "T", "EY2", "T", "AH0", "V", "DH", "IH0", "AA1", "R", "T"),
            lexicon.pronounce("state-of-the-art"),
        )
        assertArrayEquals(
            ids("AE1", "P", "AH0", "L", "HH", "AH0", "L", "OW1"),
            lexicon.pronounce("apple-hello"),
        )
        assertEquals(0, lexicon.pronounce("Qxjvz").size)
    }

    @Test
    fun pureChineseMatchesTheExistingFrontend() {
        val files = G2pFiles.fromDirectory(assets())
        val chinese = ChineseFrontend(files)
        val mixed = MixedSpeech(chinese, EnglishLexicon(files))
        val text = "今天天气不错。"
        assertArrayEquals(chinese.infer(text).ids, mixed.speak(text).ids)
    }

    @Test
    fun englishWordIsInsertedBesideChinese() {
        val files = G2pFiles.fromDirectory(assets())
        val chinese = ChineseFrontend(files)
        val lexicon = EnglishLexicon(files)
        val mixed = MixedSpeech(chinese, lexicon)
        val spoken = mixed.speak("我喜欢Apple。")
        val expected = chinese.phones("。我喜欢").ids + lexicon.pronounce("Apple") + chinese.phones("。").ids
        assertArrayEquals(expected, spoken.ids)
        val english = spoken.segments.filterIsInstance<SpeechSegment.English>()
        assertEquals(1, english.size)
        assertEquals(lexicon.pronounce("Apple").size, english.single().count)
    }

    @Test
    fun unknownEnglishLeavesTheChinesePercent() {
        val files = G2pFiles.fromDirectory(assets())
        val chinese = ChineseFrontend(files)
        val mixed = MixedSpeech(chinese, EnglishLexicon(files))
        val spoken = mixed.speak("Qxjvz 100%")
        val chineseIds = spoken.segments.filterIsInstance<SpeechSegment.Chinese>().flatMap { it.result.ids.toList() }
        assertEquals(chinese.phones("。").ids.toList() + chinese.phones(" 100%").ids.toList(), chineseIds)
        assertTrue(spoken.segments.none { it is SpeechSegment.English })
        assertTrue(spoken.segments.filterIsInstance<SpeechSegment.Chinese>().last().result.normalized.contains("百分"))
    }

    private fun ids(vararg phones: String): IntArray {
        val symbols = HashMap<String, Int>()
        File(assets(), "symbols.txt").readLines().filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            symbols[line] = index
        }
        return IntArray(phones.size) { symbols.getValue(phones[it]) }
    }

    private fun assets(): File = listOf(
        File("src/main/assets/g2p"),
        File("app/src/main/assets/g2p"),
        File("android/app/app/src/main/assets/g2p"),
    ).first { it.isDirectory }
}
