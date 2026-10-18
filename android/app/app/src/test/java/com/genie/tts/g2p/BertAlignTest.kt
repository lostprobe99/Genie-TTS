package com.genie.tts.g2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BertAlignTest {
    @Test
    fun dropsEndsAndRepeatsRows() {
        val rows = 4
        val data = FloatArray(rows * BertAlign.DIM) { (it / BertAlign.DIM).toFloat() }
        val out = BertAlign.expand(data, rows, intArrayOf(1, 2), 3)
        requireNotNull(out)
        assertEquals(3 * BertAlign.DIM, out.size)
        assertEquals(1f, out[0])
        assertEquals(2f, out[BertAlign.DIM])
        assertEquals(2f, out[2 * BertAlign.DIM])
    }

    @Test
    fun keepsPhoneSizedMatrix() {
        val data = FloatArray(2 * BertAlign.DIM) { 4f }
        val out = BertAlign.expand(data, 2, intArrayOf(1, 1), 2)
        assertEquals(data, out)
    }

    @Test
    fun rejectsUnknownLayout() {
        val data = FloatArray(5 * BertAlign.DIM)
        assertNull(BertAlign.expand(data, 5, intArrayOf(1, 2), 3))
    }
}
