package com.genie.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadedCharacterTest {
    @Test
    fun theCacheKeyKeepsTheCharacterAndDropsTheThreadCount() {
        assertEquals("feibi", loadedCharacterId("feibi|4"))
        assertEquals("a1_b", loadedCharacterId("a1_b|1"))
        assertNull(loadedCharacterId(null))
        assertNull(loadedCharacterId("|4"))
    }
}
