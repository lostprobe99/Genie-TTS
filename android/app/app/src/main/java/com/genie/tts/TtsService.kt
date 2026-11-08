package com.genie.tts

import com.genie.tts.character.CharacterRepository
import com.genie.tts.core.LogLevel
import com.genie.tts.core.LogModule
import com.genie.tts.core.MachineProbe
import com.genie.tts.core.TtsLog
import com.genie.tts.service.ServiceStates
import com.genie.tts.service.TtsException
import com.genie.tts.service.TtsPorts
import com.genie.tts.service.TtsRequest
import com.genie.tts.service.TtsServer
import com.genie.tts.service.secondsText
import com.genie.tts.speech.CancelHandle
import com.genie.tts.speech.Engines
import com.genie.tts.speech.TextSplitter
import com.genie.tts.speech.inferenceThreads
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class TtsService : Service() {
    private val machine by lazy { MachineProbe(this) }
    private val gate = Any()
    private var server: TtsServer? = null
    private var stopping = false
    private var generation = 0
    private var listeningPort = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val port = TtsPorts.read(filesDir)
        val states = ServiceStates.store
        states.update { it.copy(port = port) }
        CharacterRepository.shared.attach(filesDir)
        TtsLog.attach(filesDir)
        val same = synchronized(gate) {
            states.current().running && server != null && listeningPort == port && !stopping
        }
        if (same) {
            states.update { it.copy(switching = false) }
            return START_STICKY
        }
        stopping = false
        enterForeground(notification())
        rebind(port)
        return START_STICKY
    }

    override fun onDestroy() {
        val current = synchronized(gate) {
            if (stopping) return
            stopping = true
            generation += 1
            val open = server
            server = null
            listeningPort = 0
            open
        }
        val states = ServiceStates.store
        states.update { it.copy(running = false, switching = false, startedAt = 0L) }
        TtsLog.add(LogLevel.INFO, LogModule.SERVICE, "接口已关闭")
        thread(name = "genie-http-stop", isDaemon = true) {
            current?.stop()
            states.update { it.copy(requests = 0, failures = 0, audioMs = 0.0) }
            Engines.closeIfIdle { states.current().running }
        }
        super.onDestroy()
    }

    private fun rebind(port: Int) {
        val (ticket, previous) = synchronized(gate) {
            generation += 1
            val open = server
            server = null
            listeningPort = 0
            generation to open
        }
        thread(name = "genie-http-bind", isDaemon = true) {
            previous?.stop()
            if (stale(ticket)) return@thread
            try {
                val created = TtsServer(
                    port,
                    ::roster,
                    ::speak,
                    log = { level, module, message -> TtsLog.add(level, module, message) },
                    onCall = { failed, spokenMs ->
                        ServiceStates.store.update { state ->
                            state.copy(
                                requests = state.requests + 1,
                                failures = state.failures + if (failed) 1 else 0,
                                audioMs = state.audioMs + spokenMs,
                            )
                        }
                    },
                )
                created.start()
                val keep = synchronized(gate) {
                    if (ticket != generation || stopping) {
                        false
                    } else {
                        server = created
                        listeningPort = port
                        true
                    }
                }
                if (!keep) {
                    created.stop()
                    return@thread
                }
                val openedAt = SystemClock.elapsedRealtime()
                ServiceStates.store.update { state ->
                    val fresh = state.startedAt == 0L
                    state.copy(
                        port = port,
                        running = true,
                        switching = false,
                        error = null,
                        startedAt = if (fresh) openedAt else state.startedAt,
                        requests = if (fresh) 0 else state.requests,
                        failures = if (fresh) 0 else state.failures,
                        audioMs = if (fresh) 0.0 else state.audioMs,
                    )
                }
                TtsLog.add(LogLevel.INFO, LogModule.SERVICE, "接口已打开 端口 $port")
                logMachine("开机", "此前")
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            } catch (error: Exception) {
                if (stale(ticket)) return@thread
                val message = if (error is java.net.BindException) {
                    "端口 $port 已被占用"
                } else {
                    "接口没有打开"
                }
                ServiceStates.store.update {
                    it.copy(
                        running = false,
                        switching = false,
                        startedAt = 0L,
                        requests = 0,
                        failures = 0,
                        audioMs = 0.0,
                        error = message,
                    )
                }
                TtsLog.add(LogLevel.ERROR, LogModule.SERVICE, message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun logMachine(label: String, cpuPrefix: String) {
        val read = runCatching { machine.snapshot(label, cpuPrefix) }
        val line = read.getOrElse { "$label 机器读不到" }
        val level = if (read.isSuccess) LogLevel.INFO else LogLevel.WARN
        TtsLog.add(level, LogModule.MONITOR, line)
    }

    private fun stale(ticket: Int): Boolean {
        return synchronized(gate) { ticket != generation || stopping }
    }

    private fun roster(): Map<String, Int> {
        return CharacterRepository.shared.all().associate { it.id to it.sampleRate.coerceAtLeast(1) }
    }

    private fun speak(request: TtsRequest, stop: () -> Boolean, emit: (String, FloatArray) -> Unit) {
        val character = CharacterRepository.shared.find(request.characterName)
            ?: throw TtsException(404, "character '${request.characterName}' not found")
        val pieces = if (request.splitSentence) {
            TextSplitter().split(request.text)
        } else {
            listOf(request.text)
        }
        if (pieces.isEmpty()) throw TtsException(400, "Text is required")
        val speech = Engines.get(filesDir) { assets.open(it) }
        val threads = inferenceThreads()
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "genie:tts")
        lock.setReferenceCounted(false)
        lock.acquire(15 * 60 * 1000L)
        logMachine("请求前", "此前")
        try {
            Engines.scheduler.call(CancelHandle(stop)) { stopNow ->
                val prepareStarted = System.nanoTime()
                try {
                    speech.prepare(character, threads)
                } catch (error: Exception) {
                    throw TtsException(503, "failed to load character '${request.characterName}': ${error.message}")
                }
                val prepareMs = (System.nanoTime() - prepareStarted) / 1_000_000.0
                TtsLog.add(
                    LogLevel.INFO,
                    LogModule.SPEECH,
                    "模型就绪 ${secondsText(prepareMs)} 秒 ${request.characterName} ${pieces.size}句 ${threads}线程",
                )
                speech.synthesize(
                    character,
                    pieces,
                    speed = 1.0,
                    threads = threads,
                    onPiece = { _, clip ->
                        val samples = clip.samples ?: return@synthesize
                        emit(clip.text, samples)
                    },
                    stop = stopNow,
                    failFast = true,
                )
            }
        } finally {
            logMachine("请求后", "本段")
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
            putExtra(EXTRA_PAGE, PAGE_SERVICE)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setLargeIcon(notificationLogo())
            .setContentTitle("朗读接口开着")
            .setContentText("http://127.0.0.1:${ServiceStates.store.current().port}/tts")
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(R.drawable.ic_notify_stop, "停止", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationLogo(): Bitmap {
        val size = (64 * resources.displayMetrics.density).toInt().coerceAtLeast(128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        })
        getDrawable(R.drawable.bg_launcher)?.also {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
        getDrawable(R.drawable.ic_launcher_foreground)?.also {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
        return bitmap
    }

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_SERVICE = "service"
        private const val ACTION_STOP = "com.genie.tts.STOP"
        private const val CHANNEL = "tts-service"
        private const val NOTIFICATION_ID = 41
    }
}
