package com.genie.tts.ui

import com.genie.tts.R
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** 导入页和改名页共用的参考音播放。同一时间只放一份。 */
internal class HeldAudio(private val activity: AppCompatActivity) {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var onStopped: (() -> Unit)? = null
    private var label: TextView? = null

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun bindLabel(view: TextView?) {
        label = view
    }

    fun play(file: File, onStarted: () -> Unit, onStopped: () -> Unit) {
        stop()
        this.onStopped = onStopped
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        val granted = activity.getSystemService(AudioManager::class.java)
            .requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = request
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener { activity.runOnUiThread { stop() } }
            media.prepare()
            media.start()
            onStarted()
            if (!granted) {
                Toast.makeText(activity, "声音可能被其他应用占用", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            stop()
            Toast.makeText(activity, activity.getString(R.string.import_audio_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun stop() {
        val media = player
        player = null
        media?.runCatching { stop() }
        media?.runCatching { release() }
        focus?.let { request ->
            activity.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
        }
        focus = null
        label?.text = activity.getString(R.string.preview)
        val stopped = onStopped
        onStopped = null
        stopped?.invoke()
    }
}

internal fun mediaSeconds(file: File): Double? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        if (millis == null || millis <= 0L) null else millis / 1000.0
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

internal fun formatSeconds(seconds: Double): String = "%.1f".format(java.util.Locale.US, seconds)
