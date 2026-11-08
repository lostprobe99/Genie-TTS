package com.genie.tts.speech

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放自己的线程。合成队列不再被播放占住。
 * [start] 会停掉上一轮。停掉的那一轮不再回调完成。
 */
internal class AudioPlayer {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "genie-play").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)

    fun start(
        block: (stop: () -> Boolean) -> Unit,
        onComplete: () -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        val ticket = generation.incrementAndGet()
        val stop = { ticket != generation.get() }
        worker.execute {
            try {
                block(stop)
                if (!stop()) onComplete()
            } catch (error: Exception) {
                if (!stop()) onError(error)
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
    }
}
