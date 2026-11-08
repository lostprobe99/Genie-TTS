package com.genie.tts.core

/**
 * 合成、导入和朗读接口会碰到的失败。
 * 现在只定义种类，调用点仍抛原来的异常；界面、HTTP 和日志的对应文案以后再接。
 */
internal sealed class AppFailure(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 用户或新的一次播放取消了当前合成。 */
    class Cancelled : AppFailure()

    /** 已经有一路合成在跑。 */
    class Busy : AppFailure()

    /** 合成超过允许的时间。 */
    class TimedOut : AppFailure()

    /** HTTP 客户端中途断开。这一次不算进失败次数。 */
    class ClientDisconnected : AppFailure()

    class CharacterNotFound(val characterId: String) : AppFailure()

    /** 角色包缺文件、文件太小，或只是 Git LFS 指针。detail 留给日志，不直接展示。 */
    class PackIncomplete(val detail: String) : AppFailure()

    /** 参考音素和角色包里的原文对不上。 */
    class ReferenceMismatch : AppFailure()

    class ModelLoadFailed(val detail: String, cause: Throwable? = null) : AppFailure(cause = cause)
}
