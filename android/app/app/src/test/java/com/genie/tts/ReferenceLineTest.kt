package com.genie.tts

import com.genie.tts.g2p.BertTokenizer
import com.genie.tts.g2p.ChineseFrontend
import com.genie.tts.g2p.G2pFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReferenceLineTest {
    @Test
    fun matchesStoredReferencePhones() {
        val g2p = listOf(
            File("src/main/assets/g2p"),
            File("app/src/main/assets/g2p"),
            File("android/onnx-demo/app/src/main/assets/g2p"),
        ).first { it.isDirectory }
        val vocab = listOf(
            File("src/main/assets/roberta/vocab.txt"),
            File("app/src/main/assets/roberta/vocab.txt"),
            File("android/onnx-demo/app/src/main/assets/roberta/vocab.txt"),
        ).first { it.isFile }
        val result = ChineseFrontend(G2pFiles.fromDirectory(g2p)).phones(ReferenceLine.FEIBI)
        assertEquals(
            "在此之前,请您务必继续享受旅居拉古那的时光.",
            result.normalized,
        )
        assertEquals(
            "319 105 124 163 320 211 247 177 1 247 203 227 197 316 258 122 169 221 169 317 299 317 183 251 243 224 298 221 296 224 97 156 257 227 100 127 134 251 212 156 275 3",
            result.ids.joinToString(" "),
        )
        assertEquals(
            "2 2 2 2 1 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 1",
            result.word2ph.joinToString(" "),
        )
        val tokens = BertTokenizer.load(vocab).encode(result.normalized)
        assertEquals(
            "101 1762 3634 722 1184 117 6435 2644 1218 2553 5326 5330 775 1358 3180 2233 2861 1367 6929 4638 3198 1045 119 102",
            tokens.joinToString(" "),
        )
        assertEquals(result.word2ph.size + 2, tokens.size)
    }

    @Test
    fun sanyueqiReferencePhones() {
        val g2p = listOf(
            File("src/main/assets/g2p"),
            File("app/src/main/assets/g2p"),
            File("android/onnx-demo/app/src/main/assets/g2p"),
        ).first { it.isDirectory }
        val result = ChineseFrontend(G2pFiles.fromDirectory(g2p)).phones("倒是不忙，可他不喜欢聊天")
        assertEquals("倒是不忙,可他不喜欢聊天", result.normalized)
        assertEquals(
            "127 120 251 214 122 258 225 113 1 222 132 252 97 122 258 317 168 158 274 224 187 252 176",
            result.ids.joinToString(" "),
        )
    }
}
