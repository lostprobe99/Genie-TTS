package com.genie.tts.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NewlySpokenTokensTest {
    @Test
    fun stoppingOnTheFirstStepDropsTheReference() {
        val prompt = LongArray(80) { it.toLong() }
        assertArrayEquals(LongArray(0), newlySpokenTokens(prompt, 0))
    }

    @Test
    fun laterStepsKeepOnlyTheNewTokens() {
        val values = longArrayOf(9, 8, 7, 4, 5, 1024)
        assertArrayEquals(longArrayOf(4, 5, 0), newlySpokenTokens(values, 3))
    }

    @Test
    fun anEarlyEndMarkerCutsTheNewTokens() {
        val values = longArrayOf(9, 8, 4, 1024, 5)
        assertArrayEquals(longArrayOf(4), newlySpokenTokens(values, 3))
    }
}
