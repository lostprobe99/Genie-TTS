package com.genie.tts.core

import android.os.Handler
import android.os.Looper

/** 保存一份状态，并通知已登记的监听者。一次更新换一整份，监听可以注销。 */
internal class StateStore<T>(
    initial: T,
    private val post: (() -> Unit) -> Unit = { block ->
        Handler(Looper.getMainLooper()).post(block)
    },
) {
    private val lock = Any()
    private var state = initial
    private var nextId = 0L
    private val listeners = LinkedHashMap<Long, (T) -> Unit>()

    fun current(): T = synchronized(lock) { state }

    fun update(block: (T) -> T): T {
        synchronized(lock) {
            state = block(state)
        }
        dispatch()
        return current()
    }

    fun listen(listener: (T) -> Unit): () -> Unit {
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
        val deliver = {
            val snapshot = current()
            val live = synchronized(lock) { listeners.values.toList() }
            live.forEach { it(snapshot) }
        }
        if (isOnMainThread()) deliver() else post(deliver)
    }

    private fun isOnMainThread(): Boolean = try {
        val main = Looper.getMainLooper() ?: return false
        Looper.myLooper() == main
    } catch (_: RuntimeException) {
        false
    }
}
