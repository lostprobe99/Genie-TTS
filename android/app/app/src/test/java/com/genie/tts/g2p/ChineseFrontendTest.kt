package com.genie.tts.g2p

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ChineseFrontendTest {
    @Test
    fun matchesDesktopFrontend() {
        val assets = listOf(
            File("src/main/assets/g2p"),
            File("app/src/main/assets/g2p"),
            File("android/onnx-demo/app/src/main/assets/g2p"),
        ).first { it.isDirectory }
        val goldenFile = listOf(
            File("src/test/resources/golden_phones.txt"),
            File("app/src/test/resources/golden_phones.txt"),
            File("android/onnx-demo/app/src/test/resources/golden_phones.txt"),
        ).first { it.isFile }
        val frontend = ChineseFrontend(G2pFiles.fromDirectory(assets))
        val blocks = goldenFile.readText().trim().split(Regex("\n\n+"))
        assertEquals(8, blocks.size)
        for (block in blocks) {
            val lines = block.lines()
            val text = lines[0].removePrefix("TEXT ")
            val result = frontend.infer(text)
            assertEquals(text, lines[1].removePrefix("NORM "), result.normalized)
            assertEquals(text, lines[2].removePrefix("PHONES "), result.phones.joinToString(" "))
            assertEquals(text, lines[3].removePrefix("IDS "), result.ids.joinToString(" "))
            assertEquals(text, lines[4].removePrefix("WORD2PH "), result.word2ph.joinToString(" "))
            assertEquals(text, result.ids.size, result.word2ph.sum())
        }
    }
}
