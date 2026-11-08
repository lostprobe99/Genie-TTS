package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TaskLaneTest {
    @Test
    fun onlyOneCallerOccupiesTheLane() {
        val lane = TaskLane(StateStore(false) { it() })
        val won = AtomicInteger()
        val workers = (1..8).map {
            thread {
                if (lane.tryOccupy()) won.incrementAndGet()
            }
        }
        workers.forEach { it.join() }
        assertEquals(1, won.get())
        assertTrue(lane.isBusy())
        lane.release()
        assertFalse(lane.isBusy())
        assertTrue(lane.tryOccupy())
    }

    @Test
    fun listenersSeeTheLaneOpenAndClose() {
        val lane = TaskLane(StateStore(false) { it() })
        val seen = mutableListOf<Boolean>()
        val stop = lane.listen { seen += it }
        lane.occupy()
        lane.release()
        stop()
        lane.occupy()
        assertEquals(listOf(true, false), seen)
    }
}
