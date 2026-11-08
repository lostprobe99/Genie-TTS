package com.genie.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InferenceResourcesTest {
    @Test
    fun threadCountStaysBetweenOneAndSix() {
        assertTrue(inferenceThreads() in 1..6)
    }

    @Test
    fun fromFileChecksDtypeAndSizeTheSameWayAsThePack() {
        val root = File("build/owned-tensor").apply { deleteRecursively(); mkdirs() }
        val file = File(root, "ref_seq.bin").apply { writeBytes(ByteArray(16)) }
        val tensor = OwnedTensor.fromFile(file, "int64", longArrayOf(1, 2))
        assertEquals(16, tensor.bytes.capacity())

        val dtype = assertThrows(IllegalStateException::class.java) {
            OwnedTensor.fromFile(file, "float16", longArrayOf(1))
        }
        assertTrue(dtype.message.orEmpty().contains("不支持的 dtype"))

        val size = assertThrows(IllegalStateException::class.java) {
            OwnedTensor.fromFile(file, "float32", longArrayOf(1))
        }
        assertTrue(size.message.orEmpty().contains("应为 4"))
    }

    @Test
    fun loadingDiscardsTheUnusedOptimizedFiles() {
        val root = File("build/optimized-cache").apply { deleteRecursively(); mkdirs() }
        val optimized = File(root, "optimized").apply { mkdirs() }
        File(optimized, "vits_fp32.onnx.t4.opt").writeBytes(ByteArray(8))

        discardOptimizedModels(root)

        assertFalse(optimized.exists())
        discardOptimizedModels(root)
    }

    @Test
    fun loadedCharacterReadsTheCaseOncePerInstance() {
        val root = File("build/loaded-character").apply { deleteRecursively(); mkdirs() }
        File(root, "manifest.json").writeText("""{"sample_rate":32000,"tensors":{}}""")
        val loaded = LoadedCharacter("feibi", root)
        assertEquals("feibi", loaded.id)
        assertEquals(32000, loaded.case.sampleRate)
        assertTrue(loaded.case.tensors.isEmpty())
    }
}
