package com.genie.tts.service

import com.genie.tts.MainActivity
import com.genie.tts.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.os.Build
import androidx.core.app.NotificationCompat

/** 朗读接口开着时的常驻通知。点通知回到服务页，点停止就关掉接口。 */
internal object ServiceNotification {
    const val ACTION_STOP = "com.genie.tts.STOP"
    const val ID = 41
    private const val CHANNEL = "tts-service"

    fun build(context: Context, port: Int): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.notice_channel), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TtsService.EXTRA_PAGE, TtsService.PAGE_SERVICE)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, TtsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setLargeIcon(logo(context))
            .setContentTitle(context.getString(R.string.notice_title))
            .setContentText("http://127.0.0.1:$port/tts")
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(R.drawable.ic_notify_stop, context.getString(R.string.stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun logo(context: Context): Bitmap {
        val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        })
        context.getDrawable(R.drawable.bg_launcher)?.also {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
        context.getDrawable(R.drawable.ic_launcher_foreground)?.also {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
        return bitmap
    }
}
