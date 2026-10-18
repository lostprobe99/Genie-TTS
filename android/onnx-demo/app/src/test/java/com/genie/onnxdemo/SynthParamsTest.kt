package com.genie.onnxdemo

import org.junit.Assert.assertEquals
import org.junit.Test

class SynthParamsTest {
    @Test
    fun defaultsMatchTheDesktopCall() {
        val params = SynthParams.fromProgress(8, 4, 30, 0)
        assertEquals(1.0, params.speed, 0.001)
        assertEquals(5, params.minSentence)
        assertEquals(40, params.maxSentence)
        assertEquals(0, params.threads)
    }

    @Test
    fun slowerSpeedAndShorterSentences() {
        val params = SynthParams.fromProgress(4, 2, 20, 4)
        assertEquals(0.80, params.speed, 0.001)
        assertEquals(3, params.minSentence)
        assertEquals(30, params.splitMax())
        assertEquals(4, params.threads)
    }
}
