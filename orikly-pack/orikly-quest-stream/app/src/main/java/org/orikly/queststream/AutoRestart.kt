package org.orikly.queststream

import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Автоматический перезапуск стрима (0.5.1): после включения очков, после обновления приложения
 * и после выхода из сна — полностью заново: новое разрешение на захват, новый кодировщик, новое соединение.
 * Без единого нажатия на очках.
 *
 * Работает только после трёх ADB-команд (один раз на каждых очках):
 *   appops set org.orikly.queststream PROJECT_MEDIA allow        — захват экрана без окна «Empezar»
 *   appops set org.orikly.queststream SYSTEM_ALERT_WINDOW allow  — приложению можно открыть окно из фона
 *   dumpsys deviceidle whitelist +org.orikly.queststream        — система не усыпляет приложение
 * Без первых двух автоперезапуск не включается (иначе на очках висело бы окно подтверждения).
 *
 * Пока стрим идёт, здесь ничего не работает: только ждём событие «экран включился».
 */
object AutoRestart {
    private const val TAG = "OriklyAuto"
    private const val PREFS = "orikly"
    private const val KEY_ENABLED = "auto_restart"

    /** Экран был выключен хотя бы столько — это сон, перезапускаем. Короче — очки просто поправили. */
    const val SLEEP_MIN_MS = 10_000L
    /** После включения экрана ждём, пока очки придут в себя (Wi-Fi, система). */
    const val WAKE_DELAY_MS = 2_000L
    /** После загрузки очков ждём дольше: система ещё запускается. */
    const val BOOT_DELAY_MS = 5_000L
    /** Не чаще одного перезапуска за столько (экран может «мигнуть» несколько раз подряд). */
    private const val MIN_GAP_MS = 10_000L

    @Volatile private var lastRequestAt = 0L
    /** Для экрана приложения: когда и почему был последний автоперезапуск. */
    @Volatile var lastInfo = ""

    /** Выключается кнопкой «Detener» на очках, включается снова кнопкой «Iniciar». По умолчанию включено. */
    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    /** ADB 1: захват без окна подтверждения. */
    fun hasCaptureGrant(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:project_media", Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** ADB 2: открыть окно из фона (без этого Android не даст начать захват, пока приложение закрыто). */
    fun hasWindowGrant(ctx: Context) = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** ADB 3: приложение не усыпляется системой. */
    fun hasBatteryGrant(ctx: Context) = runCatching {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    fun hasGrants(ctx: Context) = hasCaptureGrant(ctx) && hasWindowGrant(ctx)

    fun ready(ctx: Context) = isEnabled(ctx) && hasGrants(ctx)

    /** Полный перезапуск стрима: новое разрешение на захват → CaptureService начинает всё заново. */
    fun request(ctx: Context, reason: String) {
        if (!ready(ctx)) {
            Log.i(TAG, "restart ($reason) skipped: enabled=${isEnabled(ctx)} capture=${hasCaptureGrant(ctx)} window=${hasWindowGrant(ctx)}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequestAt < MIN_GAP_MS) return
        lastRequestAt = now
        lastInfo = "$reason, ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
        Log.i(TAG, "restart: $reason")
        runCatching {
            ctx.startActivity(
                Intent(ctx, RestartActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            )
        }.onFailure { Log.w(TAG, "cannot open restart activity", it) }
    }
}

/**
 * Невидимое окно: получает новое разрешение на захват и сразу закрывается. После ADB-команды
 * PROJECT_MEDIA система отдаёт разрешение сразу, без окна «Empezar».
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // пересоздано системой — результат придёт сюда же
        runCatching {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }.onFailure {
            Log.w("OriklyAuto", "cannot request capture", it)
            close()
        }
    }

    @Deprecated("Activity API без androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            // Если стрим идёт — CaptureService остановит старый захват, кодировщик и соединение и начнёт заново.
            CaptureService.start(this, resultCode, data)
            WakeService.start(this)
        } else {
            Log.w("OriklyAuto", "capture not granted: result $resultCode")
        }
        close()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ_CAPTURE = 1
    }
}

/** После включения очков и после обновления приложения (HMS): запустить стрим. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "encendido"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "actualización"
            else -> return
        }
        if (AutoRestart.ready(ctx)) WakeService.start(ctx, reason)
    }
}

/**
 * Ждёт выхода очков из сна. Событие «экран включился» Android присылает только работающему приложению,
 * поэтому служба держится всё время (своей работы у неё нет). При включении экрана после сна —
 * полный перезапуск стрима; если стрим слетел — тоже.
 */
class WakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var offSince = 0L // когда выключился экран (0 — включён)
    private var offMs = 0L
    private var registered = false

    private val check = Runnable {
        val running = StreamState.status == StreamState.Status.RUNNING
        if (!running || offMs >= AutoRestart.SLEEP_MIN_MS) {
            AutoRestart.request(this, if (running) "reposo ${offMs / 1000} s" else "reposo, transmisión caída")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (offSince == 0L) offSince = now
                    main.removeCallbacks(check)
                }
                Intent.ACTION_SCREEN_ON -> {
                    offMs = if (offSince > 0) now - offSince else 0
                    offSince = 0
                    main.removeCallbacks(check)
                    main.postDelayed(check, AutoRestart.WAKE_DELAY_MS)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reinicio automático", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Orikly Stream")
            .setContentText("Reinicio automático activo")
            .setOngoing(true)
            .build()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        }
        if (started.isFailure) {
            Log.w("OriklyAuto", "wake service", started.exceptionOrNull())
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Только системные события; Android 13+ требует указать, что чужим приложениям слать сюда нельзя.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        registered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AutoRestart.ready(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // После включения очков / обновления приложения — сразу запустить стрим.
        intent?.getStringExtra(EXTRA_REASON)?.let { reason ->
            main.postDelayed({ AutoRestart.request(this, reason) }, AutoRestart.BOOT_DELAY_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (registered) runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "auto"
        private const val NOTIF_ID = 3
        private const val EXTRA_REASON = "reason"

        /** Запустить службу, если автоперезапуск включён и ADB-разрешения есть. [reason] — сразу запустить стрим. */
        fun start(ctx: Context, reason: String? = null) {
            if (!AutoRestart.ready(ctx)) return
            val i = Intent(ctx, WakeService::class.java)
            if (reason != null) i.putExtra(EXTRA_REASON, reason)
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Log.w("OriklyAuto", "cannot start wake service", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WakeService::class.java)) }
        }
    }
}
