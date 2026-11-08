package com.genie.tts.service

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
    private val post: (() -> Unit) -> Unit = { block ->
        Handler(Looper.getMainLooper()).post(block)
    },
) {
    private val lock = Any()
    private var state = initial
    private var nextId = 0L
    private val listeners = LinkedHashMap<Long, (ServiceState) -> Unit>()

    fun current(): ServiceState = synchronized(lock) { state }

    fun update(block: (ServiceState) -> ServiceState): ServiceState {
        synchronized(lock) {
            state = block(state)
        }
        dispatch()
        return current()
    }

    fun listen(listener: (ServiceState) -> Unit): () -> Unit {
        val id = synchronized(lock) {
            val assigned = nextId
            nextId += 1
            listeners[assigned] = listener
            assigned
        }
        return { synchronized(lock) { listeners.remove(id) } }
    }

    private fun dispatch() {
        val pending = synchronized(lock) { listeners.isNotEmpty() }
        if (!pending) return
        post {
            val snapshot = current()
            val live = synchronized(lock) { listeners.values.toList() }
            live.forEach { it(snapshot) }
        }
    }
}

internal object ServiceStates {
    val store = ServiceStateStore()
}
