package com.genie.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class InferenceSchedulerTest {
    @Test
    fun oneCallRunsAtATimeAndInvalidationWaits() {
        val scheduler = InferenceScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val first = Thread {
            scheduler.call {
                order.add("synth")
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val dropped = CountDownLatch(1)
        Thread {
            scheduler.call { order.add("drop") }
            dropped.countDown()
        }.start()
        assertFalse(dropped.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(dropped.await(5, TimeUnit.SECONDS))
        first.join(5_000)
        assertEquals(listOf("synth", "drop"), order)
    }

    @Test
    fun aCallAlreadyOnTheLaneRunsInline() {
        val scheduler = InferenceScheduler()
        val order = mutableListOf<String>()
        scheduler.call {
            scheduler.call { order.add("inner") }
            order.add("outer")
        }
        assertEquals(listOf("inner", "outer"), order)
    }

    @Test
    fun cancelHandleStopsTheRunningCall() {
        val scheduler = InferenceScheduler()
        val handle = CancelHandle()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        Thread {
            scheduler.call(handle) { stop ->
                started.countDown()
                while (!stop()) Thread.sleep(5)
            }
            finished.countDown()
        }.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        handle.cancel()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(handle.isCancelled())
    }

    @Test
    fun anOutsideStopFlagCancelsWithoutCallingCancel() {
        val scheduler = InferenceScheduler()
        val outside = AtomicBoolean(false)
        val handle = CancelHandle { outside.get() }
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        Thread {
            scheduler.call(handle) { stop ->
                started.countDown()
                while (!stop()) Thread.sleep(5)
            }
            finished.countDown()
        }.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        outside.set(true)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun failuresReachTheCallerAsTheOriginalException() {
        val scheduler = InferenceScheduler()
        val error = assertThrows(IllegalStateException::class.java) {
            scheduler.call { error("角色包不完整") }
        }
        assertEquals("角色包不完整", error.message)
    }

    @Test
    fun speechRejectsCallsFromOutsideTheScheduler() {
        val speech = Speech(File("build/lane-speech")) { error("unused") }
        val rejected = assertThrows(IllegalStateException::class.java) { speech.dropEngine() }
        assertTrue(rejected.message.orEmpty().contains("推理队列"))
        InferenceScheduler().call { speech.dropEngine() }
    }
}
