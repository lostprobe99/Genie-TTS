package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StateStoreTest {
    @Test
    fun updateReplacesTheSnapshotAndCancelledListenersStop() {
        val store = StateStore(0) { it() }
        var first = 0
        var second = 0
        val stopFirst = store.listen { first = it }
        store.listen { second = it }
        store.update { it + 1 }
        stopFirst()
        store.update { it + 1 }
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(2, store.current())
    }
}
