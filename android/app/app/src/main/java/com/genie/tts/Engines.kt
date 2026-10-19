package com.genie.tts

import java.io.File
import java.io.InputStream

internal object Engines {
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

    fun closeIfIdle() {
        val closing = synchronized(gate) {
            if (activityOpen || TtsRuntime.running) return
            val current = speech
            speech = null
            current
        }
        closing?.close()
    }
}
