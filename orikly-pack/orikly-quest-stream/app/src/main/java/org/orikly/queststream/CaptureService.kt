package org.orikly.queststream

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * Foreground service: держит MediaProjection и кодирует экран в H.264 аппаратным кодировщиком.
 * Порядок по требованию Android 14: сначала startForeground(mediaProjection),
 * потом getMediaProjection(), потом registerCallback(), потом createVirtualDisplay().
 *
 * Экран идёт прямо во входную поверхность кодировщика: без копирования процессором и без JPEG.
 * Пока никто не смотрит, кодировщика нет (виртуальный экран без поверхности) — нагрузки почти нет.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var relay: RelayClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encKey = "" // размер и частота работающего кодировщика
    private var codecConfig: ByteArray? = null // SPS/PPS — кладём перед каждым ключевым кадром
    private var densityDpi = 160
    private var encFrames = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                startCapture(code, data)
            }
            ACTION_STOP -> stopCapture(byUser = true)
            // Перезапуск системой без токена бесполезен: токен одноразовый.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent?) {
        ensureChannel()
        startForeground(
            NOTIF_ID,
            buildNotification("Transmitiendo pantalla", true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (projection != null) return // уже идёт
        if (code != Activity.RESULT_OK || data == null) {
            fail("Permiso denegado")
            return
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(code, data) ?: run {
                fail("No se pudo iniciar la captura")
                return
            }
            projection = mp

            val thread = HandlerThread("capture").also { it.start() }
            worker = thread
            val h = Handler(thread.looper)
            handler = h

            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Система забрала захват (сон, штатный каст Meta) или пользователь остановил.
                    handlerMain.post { if (projection === mp) stopCapture(byUser = false) }
                }
            }, h)

            val metrics = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            StreamState.sourceWidth = metrics.widthPixels
            StreamState.sourceHeight = metrics.heightPixels
            StreamState.displayHz = display.refreshRate
            densityDpi = metrics.densityDpi

            // Виртуальный экран создаём сразу (без поверхности): кодировщик подключается, когда кто-то смотрит.
            virtualDisplay = mp.createVirtualDisplay(
                "orikly-stream", StreamState.GRID_WIDTH, StreamState.GRID_HEIGHT, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, h
            )

            StreamState.onEncoderChange = { h.post { reconcileEncoder() } }
            StreamState.onKeyFrameRequest = { h.post { requestKeyFrame() } }
            StreamState.onBitrateChange = { h.post { applyBitrate() } }
            startFpsMeter(h)
            startAppWatcher(h)

            acquireLocks()
            if (BuildConfig.RELAY_URL.isNotBlank()) {
                relay = RelayClient(this, BuildConfig.RELAY_URL, BuildConfig.STREAM_KEY).also { it.start() }
            }

            StreamState.errorText = null
            StreamState.status = StreamState.Status.RUNNING
            Log.i(TAG, "capture started, screen ${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- кодировщик ----------

    /** Включить / выключить / пересобрать кодировщик под то, что просит ноутбук. Поток захвата. */
    private fun reconcileEncoder() {
        if (projection == null) return
        if (!StreamState.relayWant) {
            stopEncoder()
            return
        }
        val hq = StreamState.relayHq
        val w = if (hq) StreamState.HQ_WIDTH else StreamState.GRID_WIDTH
        val h = if (hq) StreamState.HQ_HEIGHT else StreamState.GRID_HEIGHT
        val fps = StreamState.targetFps.coerceIn(1, StreamState.MAX_FPS)
        val key = "${w}x$h@$fps"
        if (encoder != null && key == encKey) return
        stopEncoder()
        StreamState.bitrateScale = 1.0 // новое качество — начинаем с полного битрейта
        try {
            startEncoder(w, h, fps)
            encKey = key
        } catch (e: Exception) {
            Log.e(TAG, "encoder start failed", e)
            StreamState.errorText = "Codificador: ${e.message ?: e.javaClass.simpleName}"
            stopEncoder()
        }
    }

    /** Сначала с настройками низкой задержки; если кодировщик их не принимает — только с обязательными. */
    private fun startEncoder(w: Int, h: Int, fps: Int) {
        for (full in listOf(true, false)) {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.setCallback(encoderCallback(), handler)
                codec.configure(format(w, h, fps, full), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                finishStart(codec, w, h)
                return
            } catch (e: Exception) {
                runCatching { codec.release() }
                if (!full) throw e
                Log.w(TAG, "low-latency format rejected, retrying with a basic one", e)
            }
        }
    }

    private fun encoderCallback() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {} // вход — поверхность

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== encoder) return
            try {
                val buf = c.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also { buf.get(it) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codecConfig = bytes
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val cfg = codecConfig
                        // Ключевой кадр несёт с собой настройки видео: с него браузер может начать показ.
                        val data = if (key && cfg != null) cfg + bytes else bytes
                        StreamState.publish(StreamState.Frame(key, data, SystemClock.elapsedRealtime()))
                        encFrames++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "output", e)
            } finally {
                runCatching { c.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            handler?.post { if (c === encoder) { encKey = ""; reconcileEncoder() } }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    private fun format(w: Int, h: Int, fps: Int, full: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, StreamState.currentBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, StreamState.KEY_INTERVAL_S)
            // Экран обновляется 72–90 раз в секунду: кодировщик берёт не больше fps кадров.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            // Неподвижный экран не даёт новых кадров: повторяем последний, чтобы поток не замирал.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
            if (full) {
                // Ровный битрейт, без «кадров из будущего» (B-кадров), приоритет реального времени.
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                // Baseline — самый простой профиль: его декодирует любой браузер, без B-кадров.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }

    private fun finishStart(codec: MediaCodec, w: Int, h: Int) {
        val surface = codec.createInputSurface()
        encoder = codec
        encoderSurface = surface
        codecConfig = null
        codec.start()
        virtualDisplay?.resize(w, h, densityDpi)
        virtualDisplay?.surface = surface
        StreamState.encWidth = w
        StreamState.encHeight = h
        StreamState.encoderName = runCatching { codec.name }.getOrDefault("")
        Log.i(TAG, "encoder ${codec.name} ${w}x$h @${StreamState.targetFps} fps, ${StreamState.currentBitrate()} bps")
    }

    private fun stopEncoder() {
        virtualDisplay?.surface = null
        val codec = encoder
        encoder = null
        encKey = ""
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        encoderSurface?.release()
        encoderSurface = null
        codecConfig = null
        StreamState.clearFrames()
    }

    private fun requestKeyFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    private fun applyBitrate() {
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, StreamState.currentBitrate()) })
        }
    }

    /** Раз в 2 с: сколько кадров в секунду выдаёт кодировщик. */
    private fun startFpsMeter(h: Handler) {
        encFrames = 0
        var lastCount = 0L
        var lastAt = SystemClock.elapsedRealtime()
        h.postDelayed(object : Runnable {
            override fun run() {
                if (projection == null) return
                val now = SystemClock.elapsedRealtime()
                StreamState.encFps = (encFrames - lastCount) * 1000.0 / maxOf(1L, now - lastAt)
                lastCount = encFrames; lastAt = now
                h.postDelayed(this, 2000)
            }
        }, 2000)
    }

    /** Раз в секунду: в каком приложении ученик (нужно разрешение Usage access). */
    private fun startAppWatcher(h: Handler) {
        AppWatcher.reset()
        h.post(object : Runnable {
            override fun run() {
                if (projection == null) return
                runCatching { AppWatcher.poll(this@CaptureService) }.onFailure { Log.w(TAG, "app watcher", it) }
                h.postDelayed(this, 1000)
            }
        })
    }

    // ---------- жизненный цикл ----------

    private fun fail(text: String) {
        StreamState.errorText = text
        releaseAll()
        StreamState.status = StreamState.Status.ERROR
        notifyStopped("Error: $text")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture(byUser: Boolean) {
        val wasRunning = projection != null
        releaseAll()
        StreamState.status = if (byUser) StreamState.Status.IDLE else StreamState.Status.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wasRunning && !byUser) notifyStopped("Transmisión detenida. Abre la app para reanudar.")
        stopSelf()
    }

    private fun releaseAll() {
        relay?.stop(); relay = null
        StreamState.onEncoderChange = null
        StreamState.onKeyFrameRequest = null
        StreamState.onBitrateChange = null
        val h = handler
        if (h != null && Thread.currentThread() !== worker) {
            // Кодировщик живёт на потоке захвата — останавливаем там же.
            val done = java.util.concurrent.CountDownLatch(1)
            h.post { stopEncoder(); done.countDown() }
            done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        } else stopEncoder()
        virtualDisplay?.release(); virtualDisplay = null
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        worker?.quitSafely(); worker = null
        handler = null
        StreamState.encFps = 0.0
        StreamState.encWidth = 0
        StreamState.encHeight = 0
        releaseLocks()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orikly:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        // LOW_LATENCY работает, пока приложение на переднем плане; HIGH_PERF — запасной вариант в фоне.
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        for (mode in listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)) {
            runCatching {
                wm.createWifiLock(mode, "orikly:stream-$mode").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()?.let { wifiLocks += it }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLocks.forEach { runCatching { if (it.isHeld) it.release() } }
        wifiLocks.clear()
    }

    override fun onDestroy() {
        if (projection != null) {
            releaseAll()
            StreamState.status = StreamState.Status.STOPPED
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transmisión", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Orikly Stream")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .build()
    }

    private fun notifyStopped(text: String) {
        ensureChannel()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_STOPPED_ID, buildNotification(text, false))
    }

    companion object {
        private const val TAG = "OriklyCapture"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 1
        private const val NOTIF_STOPPED_ID = 2
        const val ACTION_START = "org.orikly.queststream.START"
        const val ACTION_STOP = "org.orikly.queststream.STOP"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"

        private val handlerMain by lazy { Handler(android.os.Looper.getMainLooper()) }

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, code)
                    .putExtra(EXTRA_RESULT_DATA, data)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
