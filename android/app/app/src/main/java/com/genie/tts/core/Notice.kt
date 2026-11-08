package com.genie.tts.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/** 给界面看的一句提示。对话框和短提示分开，界面只负责显示。 */
internal enum class NoticeKind { Dialog, Toast }

internal data class Notice(
    val kind: NoticeKind,
    val text: String,
    val id: Long,
)

/** 最新一条提示。后写的盖住前一条，界面按 [Notice.id] 只显示一次。 */
internal class Notices(
    post: (() -> Unit) -> Unit = { block ->
        Handler(Looper.getMainLooper()).post(block)
    },
) {
    private val store = StateStore<Notice?>(null, post)
    private val ids = AtomicLong()

    fun current(): Notice? = store.current()

    fun listen(listener: (Notice?) -> Unit): () -> Unit = store.listen(listener)

    fun dialog(text: String) = publish(NoticeKind.Dialog, text)

    fun toast(text: String) = publish(NoticeKind.Toast, text)

    private fun publish(kind: NoticeKind, text: String) {
        store.update { Notice(kind, text, ids.incrementAndGet()) }
    }
}
