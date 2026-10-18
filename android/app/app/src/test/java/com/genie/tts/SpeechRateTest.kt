package com.genie.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRateTest {
    @Test
    fun unitSpeedKeepsEveryToken() {
        val tokens = longArrayOf(1, 2, 3, 4)
        assertArrayEquals(tokens, stretchTokens(tokens, 1.0))
    }

    @Test
    fun slowerSpeedRepeatsTokens() {
        val stretched = stretchTokens(longArrayOf(10, 20, 30, 40), 0.5)
        assertEquals(8, stretched.size)
        assertArrayEquals(longArrayOf(10, 10, 20, 20, 30, 30, 40, 40), stretched)
    }

    @Test
    fun fasterSpeedDropsTokens() {
        val stretched = stretchTokens(longArrayOf(10, 20, 30, 40), 2.0)
        assertEquals(2, stretched.size)
        assertArrayEquals(longArrayOf(20, 40), stretched)
    }
}
