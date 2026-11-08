package com.genie.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioPlayerTest {
    @Test
    fun stopEndsTheCurrentPlaybackWithoutCompletingIt() {
        val player = AudioPlayer()
        val started = CountDownLatch(1)
        val left = CountDownLatch(1)
        val completed = AtomicInteger(0)
        player.start(
            block = { stop ->
                started.countDown()
                while (!stop()) Thread.sleep(5)
                left.countDown()
            },
            onComplete = { completed.incrementAndGet() },
        )
        assertTrue(started.await(5, TimeUnit.SECONDS))
        player.stop()
        assertTrue(left.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(0, completed.get())
    }

    @Test
    fun aNewStartCancelsThePreviousOne() {
        val player = AudioPlayer()
        val firstEntered = CountDownLatch(1)
        val firstLeft = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        player.start(block = { stop ->
            firstEntered.countDown()
            while (!stop()) Thread.sleep(5)
            firstLeft.countDown()
        })
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        player.start(block = {}, onComplete = { secondDone.countDown() })
        assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        assertTrue(firstLeft.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aFinishedPlaybackCompletesAndAnErrorIsReported() {
        val player = AudioPlayer()
        val completed = CountDownLatch(1)
        player.start(block = {}, onComplete = { completed.countDown() })
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        val failed = CountDownLatch(1)
        var message = ""
        player.start(
            block = { throw IllegalStateException("播放没有开始") },
            onError = {
                message = it.message.orEmpty()
                failed.countDown()
            },
        )
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals("播放没有开始", message)
    }
}
