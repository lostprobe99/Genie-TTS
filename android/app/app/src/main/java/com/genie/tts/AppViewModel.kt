package com.genie.tts

import com.genie.tts.core.RunMarker
import com.genie.tts.core.TaskLane
import com.genie.tts.service.ServiceStates
import com.genie.tts.speech.Engines
import com.genie.tts.speech.PlaybackActions
import com.genie.tts.speech.SynthesisActions
import com.genie.tts.speech.playSamples
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.File
import kotlin.concurrent.thread

/**
 * 只组装页面自己用的操作，并在真正离开界面时收尾。
 * 合成、播放、角色和韵律的规则不写在这里。
 */
internal class AppViewModel(app: Application) : AndroidViewModel(app) {
    val lane = TaskLane()

    val playback = PlaybackActions(
        sink = { samples, sampleRate, stop, onStarted, onProgress ->
            playSamples(app, samples, sampleRate, stop, onStarted, onProgress)
        },
    )

    val synthesis = SynthesisActions(
        lane = lane,
        playback = playback,
        marker = RunMarker(File(app.filesDir, "last_step.txt")),
        synthesizePieces = { character, pieces, rate, threads, onMark, onPiece, stop ->
            Engines.scheduler.call {
                speech().synthesize(character, pieces, rate, threads, onMark, onPiece, stop)
            }
        },
        previewPiece = { character, threads, onMark, stop ->
            Engines.scheduler.call {
                speech().preview(character, threads, onMark, stop)
            }
        },
    )

    init {
        Engines.get(app.filesDir) { app.assets.open(it) }
    }

    override fun onCleared() {
        playback.close()
        synthesis.cancel()
        Engines.activityOpen = false
        thread(name = "genie-close", isDaemon = true) {
            Engines.closeIfIdle { ServiceStates.store.current().running }
        }
    }

    private fun speech() = Engines.get(getApplication<Application>().filesDir) {
        getApplication<Application>().assets.open(it)
    }
}
