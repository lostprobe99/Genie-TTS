package com.genie.onnxdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthParamsTest {
    @Test
    fun defaultsMatchTheBakedModel() {
        val params = SynthParams.fromProgress(8, 16, 14, 7, 4, 30, 20, 0, true)
        assertEquals(1.0, params.speed, 0.001)
        assertEquals(1.0, params.temperature, 0.001)
        assertEquals(15, params.topK)
        assertEquals(1.35, params.repetitionPenalty, 0.001)
        assertEquals(5, params.minSentence)
        assertEquals(40, params.maxSentence)
        assertEquals(500, params.maxSteps)
        assertTrue(params.samplingIsBaked)
        assertTrue(params.leadingPeriod)
    }

    @Test
    fun movedSamplingIsNotBaked() {
        val params = SynthParams.fromProgress(4, 16, 14, 7, 4, 30, 20, 0, true)
        assertEquals(0.80, params.speed, 0.001)
        assertTrue(params.samplingIsBaked)
        val hotter = params.copy(temperature = 1.2, topK = 8)
        assertFalse(hotter.samplingIsBaked)
    }
}
