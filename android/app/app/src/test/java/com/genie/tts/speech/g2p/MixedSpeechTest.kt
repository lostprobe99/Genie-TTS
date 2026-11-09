package com.genie.tts.speech.g2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun splitsNasaAndNoDot() {
        val nasa = LanguageSplit.split("NASA")
        assertEquals(1, nasa.size)
        assertEquals("NASA", (nasa.single() as TextChunk.En).word)

        val numbered = LanguageSplit.split("No. 5")
        assertEquals(2, numbered.size)
        assertEquals("No", (numbered[0] as TextChunk.En).word)
        assertEquals(". 5", (numbered[1] as TextChunk.Han).text)
    }

    @Test
    fun lexiconUsesDictionaryPossessiveAndHyphen() {
        val english = EnglishG2p(G2pFiles.fromDirectory(assets()))
        assertArrayEquals(ids("AE1", "P", "AH0", "L"), english.pronounce("Apple"))
        assertArrayEquals(ids("AE1", "P", "AH0", "L", "Z"), english.pronounce("Apple", possessive = true))
        assertArrayEquals(
            ids("S", "T", "EY2", "T", "AH0", "V", "DH", "IH0", "AA1", "R", "T"),
            english.pronounce("state-of-the-art"),
        )
        assertArrayEquals(ids("N", "AE1", "S", "AH0"), english.pronounce("NASA"))
        assertArrayEquals(ids("N", "OW1"), english.pronounce("No"))
    }

    @Test
    fun wordSegmentSplitsClearlove() {
        val english = EnglishG2p(G2pFiles.fromDirectory(assets()))
        assertArrayEquals(
            ids("K", "L", "IH1", "R", "L", "AH1", "V"),
            english.pronounce("clearlove"),
        )
    }

    @Test
    fun droppedAiIsNotDictionaryAy1() {
        val english = EnglishG2p(G2pFiles.fromDirectory(assets()))
        assertFalse(english.inDictionary("AI"))
        // 词典去掉整词后走 WordSegment：ai → a + i，不再是原来的 AY1 整词。
        assertArrayEquals(ids("AH0", "AY1"), english.pronounce("AI"))
    }

    @Test
    fun unknownWordStillProducesPhones() {
        val english = EnglishG2p(G2pFiles.fromDirectory(assets()))
        assertTrue(english.pronounce("Qxjvz").isNotEmpty())
    }

    @Test
    fun pureChineseMatchesTheExistingFrontend() {
        val files = G2pFiles.fromDirectory(assets())
        val chinese = ChineseFrontend(files)
        val mixed = MixedSpeech(chinese, EnglishG2p(files))
        val text = "今天天气不错。"
        assertArrayEquals(chinese.infer(text).ids, mixed.speak(text).ids)
    }

    @Test
    fun englishWordIsInsertedBesideChinese() {
        val files = G2pFiles.fromDirectory(assets())
        val chinese = ChineseFrontend(files)
        val english = EnglishG2p(files)
        val mixed = MixedSpeech(chinese, english)
        val spoken = mixed.speak("我喜欢Apple。")
        val expected = chinese.phones("。我喜欢").ids + english.pronounce("Apple") + chinese.phones("。").ids
        assertArrayEquals(expected, spoken.ids)
        val englishSegments = spoken.segments.filterIsInstance<SpeechSegment.English>()
        assertEquals(1, englishSegments.size)
        assertEquals(english.pronounce("Apple").size, englishSegments.single().count)
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
