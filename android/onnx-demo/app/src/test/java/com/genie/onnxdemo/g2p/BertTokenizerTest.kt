package com.genie.onnxdemo.g2p

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BertTokenizerTest {
    @Test
    fun matchesDesktopTokenizer() {
        val vocab = listOf(
            File("src/main/assets/roberta/vocab.txt"),
            File("app/src/main/assets/roberta/vocab.txt"),
            File("android/onnx-demo/app/src/main/assets/roberta/vocab.txt"),
        ).first { it.isFile }
        val tokenizer = BertTokenizer.load(vocab)
        val phones = listOf(
            File("src/test/resources/golden_phones.txt"),
            File("app/src/test/resources/golden_phones.txt"),
            File("android/onnx-demo/app/src/test/resources/golden_phones.txt"),
        ).first { it.isFile }
        for (block in phones.readText().trim().split(Regex("\n\n+"))) {
            val lines = block.lines()
            val text = lines[0].removePrefix("TEXT ")
            val ids = tokenizer.encode(lines[1].removePrefix("NORM "))
            assertEquals(text, lines[5].removePrefix("TOKENS "), ids.joinToString(" "))
        }
        val extras = listOf(
            File("src/test/resources/golden_tokens.txt"),
            File("app/src/test/resources/golden_tokens.txt"),
            File("android/onnx-demo/app/src/test/resources/golden_tokens.txt"),
        ).first { it.isFile }
        for (block in extras.readText().trim().split(Regex("\n\n+"))) {
            val lines = block.lines()
            val text = lines[0].removePrefix("TEXT ")
            val ids = tokenizer.encode(text)
            assertEquals(text, lines[1].removePrefix("IDS "), ids.joinToString(" "))
        }
    }
}
