package com.genie.onnxdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnnxSamplingTest {
    @Test
    fun patchRewritesOnlyTheSamplingConstants() {
        val edits = samplingEdits(DecoderKind.STAGE, temperature = 0.8, topK = 8, penalty = 1.1)
        val file = File("build/sampling-patch.bin")
        file.parentFile?.mkdirs()
        val body = ByteArray(0)
        val pieces = ArrayList<ByteArray>()
        pieces.add("prefix-".toByteArray())
        edits.forEach { (marker, _) ->
            pieces.add(marker)
            pieces.add(ByteArray(if (marker.last() == 4.toByte()) 4 else 8) { 0x11 })
            pieces.add("-gap-".toByteArray())
        }
        file.writeBytes(pieces.fold(body) { acc, part -> acc + part })

        val dest = File("build/sampling-patch-out.bin")
        assertTrue(patchModel(file, dest, edits))
        val patched = dest.readBytes()
        edits.forEach { (marker, payload) ->
            val at = indexOfSlice(patched, patched.size, marker)
            assertTrue(at >= 0)
            val found = patched.copyOfRange(at + marker.size, at + marker.size + payload.size)
            assertTrue(found.contentEquals(payload))
        }
        val temperature = ByteBuffer.wrap(edits[2].second).order(ByteOrder.LITTLE_ENDIAN).float
        val topK = ByteBuffer.wrap(edits[3].second).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(0.8f, temperature, 0.0001f)
        assertEquals(8L, topK)
    }

    @Test
    fun markerCanSpanAReadBoundary() {
        val marker = "MARKER".toByteArray()
        val file = File("build/marker-boundary.bin")
        file.parentFile?.mkdirs()
        val padding = ByteArray(64 * 1024 + marker.size - 1) { 'a'.code.toByte() }
        file.writeBytes(padding + marker + "tail".toByteArray())
        assertEquals(padding.size.toLong(), findOffset(file, marker))
    }
}
