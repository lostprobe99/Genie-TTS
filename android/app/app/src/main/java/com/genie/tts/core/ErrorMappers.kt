package com.genie.tts.core

import com.genie.tts.speech.RefMismatch
import com.genie.tts.speech.SynthesisCancelled

/** 把抛出来的异常收成一种 [AppFailure]。界面、接口和日志都从这里往下看。 */
internal fun Throwable.asFailure(): AppFailure = when (this) {
    is AppFailure -> this
    is RefMismatch -> AppFailure.ReferenceMismatch()
    is SynthesisCancelled -> AppFailure.Cancelled()
    else -> AppFailure.ModelLoadFailed(message?.trim().orEmpty().ifBlank { "失败" }, this)
}

/** 给界面看的一句中文。角色包的细节不放在这里。 */
internal fun uiText(error: Throwable): String {
    val failure = error.asFailure()
    val spoken = when (failure) {
        is AppFailure.PackIncomplete -> "角色包不完整，请重新导入"
        is AppFailure.ReferenceMismatch -> "参考音素和角色包里的原文对不上，请重新导入这个角色"
        is AppFailure.CharacterNotFound -> "没有这个角色"
        is AppFailure.Cancelled, is AppFailure.ClientDisconnected -> "已停止"
        is AppFailure.Busy -> "正在合成"
        is AppFailure.TimedOut, is AppFailure.ModelLoadFailed -> "合成没有完成"
    }
    if (failure is AppFailure.ModelLoadFailed && error !is AppFailure) {
        val detail = failure.detail
        if (detail.any { it.code > 0x3000 }) return detail
    }
    return spoken
}

/** 某一句失败时给分句列表看的字。整次合成的提示用 [uiText]。 */
internal fun sentenceUiText(error: Throwable): String = when (error.asFailure()) {
    is AppFailure.PackIncomplete -> "角色包不完整，请重新导入"
    is AppFailure.ReferenceMismatch -> "参考音素和角色包里的原文对不上，请重新导入这个角色"
    else -> "这句没有读出来"
}

internal data class HttpFailure(val status: Int, val message: String)

/** 给 HTTP 响应的状态和英文说明。客户端断开由接口自己记，不走这里。 */
internal fun httpFailure(error: Throwable): HttpFailure = when (val failure = error.asFailure()) {
    is AppFailure.CharacterNotFound -> HttpFailure(404, "character '${failure.characterId}' not found")
    is AppFailure.PackIncomplete -> HttpFailure(503, "character pack is incomplete")
    is AppFailure.ReferenceMismatch -> HttpFailure(503, "reference text does not match")
    else -> HttpFailure(503, "failed")
}

/** 写进日志的失败说明，带上种类和细节。 */
internal fun failureLog(error: Throwable): String = when (val failure = error.asFailure()) {
    is AppFailure.PackIncomplete -> "角色包不完整 ${failure.detail}"
    is AppFailure.ReferenceMismatch -> "参考不匹配"
    is AppFailure.CharacterNotFound -> "没有角色 ${failure.characterId}"
    is AppFailure.ModelLoadFailed -> failure.detail.ifBlank { "失败" }
    is AppFailure.Cancelled -> "已取消"
    is AppFailure.ClientDisconnected -> "客户端断开"
    is AppFailure.Busy -> "正在合成"
    is AppFailure.TimedOut -> "合成超时"
}
