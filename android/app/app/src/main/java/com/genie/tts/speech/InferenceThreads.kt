package com.genie.tts.speech

import kotlin.math.max
import kotlin.math.min

/** 主屏和朗读接口共用的推理线程数。 */
internal fun inferenceThreads(): Int = min(6, max(1, Runtime.getRuntime().availableProcessors()))
