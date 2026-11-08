package com.genie.tts.speech

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val inferenceLane = ThreadLocal<Boolean>()

internal fun requireInferenceLane() {
    check(inferenceLane.get() == true) { "合成只能在推理队列上跑" }
}

/** 取消当前这一路合成。`also` 用来接接口那边已有的断开判断。 */
internal class CancelHandle(private val also: () -> Boolean = { false }) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get() || also()
}

/**
 * 主屏、试听和接口共用的合成队列。同一时间只跑一路。
 * 播放不在这条队列上。
 */
internal class InferenceScheduler {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "genie-infer").apply { isDaemon = true }
    }

    fun <T> call(block: () -> T): T = invoke(block)

    fun <T> call(handle: CancelHandle, block: (() -> Boolean) -> T): T {
        return invoke { block { handle.isCancelled() } }
    }

    private fun <T> invoke(block: () -> T): T {
        if (inferenceLane.get() == true) return block()
        try {
            return executor.submit(Callable {
                inferenceLane.set(true)
                try {
                    block()
                } finally {
                    inferenceLane.remove()
                }
            }).get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }
}
