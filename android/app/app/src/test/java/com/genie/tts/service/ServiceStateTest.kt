package com.genie.tts.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class ServiceStateTest {
    @Test
    fun updateReplacesTheWholeSnapshot() {
        val store = ServiceStateStore(post = { it() })
        store.update { it.copy(port = 9000, running = true, requests = 2, audioMs = 1.5) }
        val state = store.current()
        assertEquals(9000, state.port)
        assertTrue(state.running)
        assertEquals(2, state.requests)
        assertEquals(1.5, state.audioMs, 0.0)
    }

    @Test
    fun listenersCanBeCancelled() {
        val store = ServiceStateStore(post = { it() })
        var first = 0
        var second = 0
        val stopFirst = store.listen { first += 1 }
        store.listen { second += 1 }
        store.update { it.copy(requests = it.requests + 1) }
        stopFirst()
        store.update { it.copy(requests = it.requests + 1) }
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(2, store.current().requests)
    }

    @Test
    fun concurrentCountsAreNotLost() {
        val store = ServiceStateStore(post = { it() })
        val workers = (1..8).map {
            thread {
                repeat(100) {
                    store.update { state -> state.copy(requests = state.requests + 1) }
                }
            }
        }
        workers.forEach { it.join() }
        assertEquals(800, store.current().requests)
    }
}
