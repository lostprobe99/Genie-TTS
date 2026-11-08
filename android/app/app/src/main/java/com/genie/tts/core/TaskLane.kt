package com.genie.tts.core

/**
 * 界面上同一时间只做一件耗时的事：合成、试听、导入、删除、下载。
 * 播放不算在里面。已经占着时，新的请求直接放弃。
 */
internal class TaskLane(
    private val store: StateStore<Boolean> = StateStore(false),
) {
    fun isBusy(): Boolean = store.current()

    fun listen(listener: (Boolean) -> Unit): () -> Unit = store.listen(listener)

    /** 没人占着就占上并返回 true。已经有人占着就返回 false，不改状态。 */
    fun tryOccupy(): Boolean {
        var acquired = false
        store.update { busy ->
            if (busy) {
                busy
            } else {
                acquired = true
                true
            }
        }
        return acquired
    }

    fun occupy() {
        store.update { true }
    }

    fun release() {
        store.update { false }
    }
}
