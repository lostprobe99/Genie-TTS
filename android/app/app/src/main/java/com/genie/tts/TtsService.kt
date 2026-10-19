package com.genie.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

internal object TtsRuntime {
    const val PORT = 8001
    const val EXTRA_PAGE = "page"
    const val PAGE_SERVICE = "service"

    @Volatile
    var running = false

    @Volatile
    var error: String? = null

    var listener: (() -> Unit)? = null

    fun publish() {
        val current = listener ?: return
        Handler(Looper.getMainLooper()).post(current)
    }
}

class TtsService : Service() {
    private var server: TtsServer? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (TtsRuntime.running && server != null) return START_STICKY
        stopping = false
        enterForeground(notification())
        try {
            val created = TtsServer(TtsRuntime.PORT, ::roster, ::speak)
            created.start()
            server = created
            TtsRuntime.running = true
            TtsRuntime.error = null
        } catch (error: Exception) {
            TtsRuntime.running = false
            TtsRuntime.error = if (error.message?.contains("EADDRINUSE") == true || error is java.net.BindException) {
                "端口 ${TtsRuntime.PORT} 已被占用"
            } else {
                "接口没有打开"
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        TtsRuntime.publish()
        return START_STICKY
    }

    override fun onDestroy() {
        if (stopping) return
        stopping = true
        TtsRuntime.running = false
        TtsRuntime.publish()
        val current = server
        server = null
        thread(name = "genie-http-stop", isDaemon = true) {
            current?.stop()
            Engines.closeIfIdle()
        }
        super.onDestroy()
    }

    private fun roster(): Map<String, Int> {
        return CharacterPack.list(filesDir).associate { info ->
            val rate = runCatching {
                JSONObject(File(info.root, "manifest.json").readText()).optInt("sample_rate", 32000)
            }.getOrDefault(32000)
            info.id to rate.coerceAtLeast(1)
        }
    }

    private fun speak(request: TtsRequest, stop: () -> Boolean, emit: (FloatArray) -> Unit) {
        val character = CharacterPack.list(filesDir).find { it.id == request.characterName }
            ?: throw TtsException(404, "character '${request.characterName}' not found")
        val pieces = if (request.splitSentence) {
            TextSplitter().split(request.text)
        } else {
            listOf(request.text)
        }
        if (pieces.isEmpty()) throw TtsException(400, "Text is required")
        val speech = Engines.get(filesDir) { assets.open(it) }
        val threads = min(6, max(1, Runtime.getRuntime().availableProcessors()))
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "genie:tts")
        lock.setReferenceCounted(false)
        lock.acquire(15 * 60 * 1000L)
        try {
            try {
                speech.prepare(character, threads)
            } catch (error: Exception) {
                throw TtsException(503, "failed to load character '${request.characterName}': ${error.message}")
            }
            speech.synthesize(
                character,
                pieces,
                speed = 1.0,
                threads = threads,
                onPiece = { _, clip ->
                    val samples = clip.samples ?: return@synthesize
                    emit(samples)
                },
                stop = stop,
                failFast = true,
            )
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun notification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, "朗读接口", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TtsRuntime.EXTRA_PAGE, TtsRuntime.PAGE_SERVICE)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("朗读接口开着")
            .setContentText("http://127.0.0.1:${TtsRuntime.PORT}/tts")
            .setOngoing(true)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL = "tts-service"
        const val NOTIFICATION_ID = 41
    }
}
