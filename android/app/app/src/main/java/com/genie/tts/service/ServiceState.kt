package com.genie.tts.service

import com.genie.tts.core.StateStore
import android.os.Handler
import android.os.Looper

/** 接口开关、端口、运行时间和统计。改状态时换一份新的，不改原来那份。 */
internal data class ServiceState(
    val port: Int = TtsPorts.DEFAULT,
    val running: Boolean = false,
    val startedAt: Long = 0L,
    val switching: Boolean = false,
    val error: String? = null,
    val requests: Int = 0,
    val failures: Int = 0,
    val audioMs: Double = 0.0,
)

/**
 * 保存当前 [ServiceState]，并通知已登记的监听者。
 * 监听可以注销。一次更新只换一份完整状态，统计不会和开关各写各的。
 */
internal class ServiceStateStore(
    initial: ServiceState = ServiceState(),
    post: (() -> Unit) -> Unit = { block ->
        Handler(Looper.getMainLooper()).post(block)
    },
) {
    private val store = StateStore(initial, post)

    fun current(): ServiceState = store.current()

    fun update(block: (ServiceState) -> ServiceState): ServiceState = store.update(block)

    fun listen(listener: (ServiceState) -> Unit): () -> Unit = store.listen(listener)
}

internal object ServiceStates {
    val store = ServiceStateStore()
}
