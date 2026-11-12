package org.orikly.queststream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Автозапуск стрима после включения очков и после выхода из сна (захват при сне слетает, а без
 * подтверждения человеком новый не начать — так устроен Android).
 *
 * Если у приложения есть разрешение «Mostrar sobre otras apps» (SYSTEM_ALERT_WINDOW), окно
 * подтверждения открывается само — остаётся нажать «Empezar». Без него Android не даёт открыть окно
 * из фона, и вместо этого появляется уведомление «Iniciar transmisión».
 */
object AutoStart {
    private const val TAG = "OriklyAuto"
    private const val CHANNEL = "autostart"
    private const val NOTIF_ID = 3
    const val EXTRA_AUTO = "auto"
    private var lastAt = 0L

    fun canOpenWindow(ctx: Context) = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    fun intent(ctx: Context) = Intent(ctx, MainActivity::class.java)
        .putExtra(EXTRA_AUTO, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Предложить запуск стрима: открыть окно, если можно, иначе уведомление. */
    fun offer(ctx: Context) {
        if (StreamState.status == StreamState.Status.RUNNING) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 5000) return // экран включается и разблокируется почти одновременно
        lastAt = now
        if (canOpenWindow(ctx) && runCatching { ctx.startActivity(intent(ctx)) }.isSuccess) {
            Log.i(TAG, "opened the capture window")
            return
        }
        notify(ctx)
    }

    private fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Iniciar transmisión", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 1, intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Orikly Stream")
            .setContentText("Pulsa para iniciar la transmisión")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    fun clearNotification(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}

/** После включения очков: предложить стрим и ждать выхода из сна. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WatchService.start(ctx)
        AutoStart.offer(ctx)
    }
}

/**
 * Лёгкая служба, пока стрим не идёт: ждёт выхода очков из сна (SCREEN_ON / USER_PRESENT приходят
 * только работающему приложению) и предлагает запуск. Останавливается, когда захват начался.
 */
class WatchService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = AutoStart.offer(ctx)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Esperando", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Orikly Stream")
            .setContentText("Lista para transmitir")
            .setOngoing(true)
            .build()
        runCatching {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { Log.w("OriklyAuto", "watch service", it); stopSelf(); return }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Системные события; Android 13+ требует явно указать, что чужим приложениям слать сюда нельзя.
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (StreamState.status == StreamState.Status.RUNNING) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "watch"
        private const val NOTIF_ID = 4
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, WatchService::class.java)) }
                .onFailure { Log.w("OriklyAuto", "cannot start watch service", it) }
        }
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WatchService::class.java)) }
        }
    }
}
