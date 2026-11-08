package com.genie.tts.speech

import java.io.File
import java.io.InputStream

internal object Engines {
    val scheduler = InferenceScheduler()
    private val gate = Any()
    private var speech: Speech? = null

    @Volatile
    var activityOpen = false

    fun get(filesDir: File, openAsset: (String) -> InputStream): Speech {
        synchronized(gate) {
            speech?.let { return it }
            return Speech(filesDir, openAsset).also { speech = it }
        }
    }

    fun closeIfIdle(inUse: () -> Boolean) {
        val closing = synchronized(gate) {
            if (activityOpen || inUse()) return
            val current = speech
            speech = null
            current
        }
        if (closing != null) scheduler.call { closing.close() }
    }
}
