package com.genie.onnxdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobertaPartsTest {
    @Test
    fun readsPartIndexFromDownloadName() {
        assertEquals(0, RobertaParts.partIndex("model_fp16.onnx.00"))
        assertEquals(12, RobertaParts.partIndex("chunks/model_fp16.onnx.12"))
        assertEquals(3, RobertaParts.partIndex("model_fp16.onnx.03.bin"))
        assertEquals(1, RobertaParts.partIndex("model_fp16.onnx.01 (1)"))
        assertNull(RobertaParts.partIndex("model_fp16.onnx"))
        assertNull(RobertaParts.partIndex("model_fp16.onnx.13"))
        assertTrue(RobertaParts.isWholeModel("roberta/model_fp16.onnx"))
    }

    @Test
    fun reportsMissingAndDuplicatePieces() {
        val indexes = (0 until RobertaParts.COUNT).flatMap { listOf(it, it) }.filter { it != 7 }
        assertEquals(listOf(7), RobertaParts.missing(indexes))
        assertEquals((0 until RobertaParts.COUNT).filter { it != 7 }, RobertaParts.duplicates(indexes))
    }
}
