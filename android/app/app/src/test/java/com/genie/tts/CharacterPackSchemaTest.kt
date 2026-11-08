package com.genie.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CharacterPackSchemaTest {
    @Test
    fun idsAreLowercasedAndTrimmedBeforeTheyAreChecked() {
        assertEquals("feibi", CharacterPackSchema.normalizeId("  FeiBi "))
        assertTrue(CharacterPackSchema.isValidId("feibi"))
        assertTrue(CharacterPackSchema.isValidId("a1_b2"))
        assertTrue(CharacterPackSchema.isValidId("a" + "b".repeat(31)))
    }

    @Test
    fun badIdsAreRejected() {
        assertFalse(CharacterPackSchema.isValidId(""))
        assertFalse(CharacterPackSchema.isValidId("1abc"))
        assertFalse(CharacterPackSchema.isValidId("_abc"))
        assertFalse(CharacterPackSchema.isValidId("Feibi"))
        assertFalse(CharacterPackSchema.isValidId("fei bi"))
        assertFalse(CharacterPackSchema.isValidId("../feibi"))
        assertFalse(CharacterPackSchema.isValidId("a" + "b".repeat(32)))
    }

    @Test
    fun theFourModelsAndFiveTensorsAreWhatThePackNeeds() {
        assertEquals(
            listOf(
                "t2s_encoder_fp32.onnx",
                "t2s_first_stage_decoder_fp32.onnx",
                "t2s_stage_decoder_fp32.onnx",
                "vits_fp32.onnx",
            ),
            CharacterPackSchema.MODEL_NAMES,
        )
        assertEquals(listOf("ref_seq", "ref_bert", "ssl_content", "ge", "ge_advanced"), CharacterPackSchema.REQUIRED_TENSORS)
    }

    @Test
    fun layoutPathsHangOffTheCharacterRoot() {
        val root = File("characters/feibi")
        assertEquals(File(root, "manifest.json"), CharacterPackSchema.manifestFile(root))
        assertEquals(File(root, "models"), CharacterPackSchema.modelsDir(root))
        assertEquals(File(root, "models/vits_fp32.onnx"), CharacterPackSchema.modelFile(root, "vits_fp32.onnx"))
        assertEquals(File(root, "tensors/ref_seq.bin"), CharacterPackSchema.refSeqFile(root))
    }

    @Test
    fun tensorBytesFollowDtypeAndShape() {
        assertEquals(2L * 1024 * 4, CharacterPackSchema.tensorBytes("float32", longArrayOf(2, 1024)))
        assertEquals(16L, CharacterPackSchema.tensorBytes("int64", longArrayOf(1, 2)))
        assertThrows(IllegalStateException::class.java) {
            CharacterPackSchema.tensorBytes("float16", longArrayOf(1))
        }
    }
}
