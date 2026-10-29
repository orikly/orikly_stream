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
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream

/**
 * Foreground service: держит MediaProjection, кодирует кадры в JPEG и HTTP-сервер.
 * Порядок по требованию Android 14: сначала startForeground(mediaProjection),
 * потом getMediaProjection(), потом registerCallback(), потом createVirtualDisplay().
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var http: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    private var rowBitmap: Bitmap? = null
    private var outBitmap: Bitmap? = null
    private var lastFrame: Bitmap? = null
    private var smallBitmap: Bitmap? = null
    private val scalePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val jpegBuffer = ByteArrayOutputStream(64 * 1024)
    private var lastFrameAt = 0L
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

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
            val handler = Handler(thread.looper)

            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Система забрала захват (например, штатный каст Meta) или пользователь остановил.
                    handlerMain.post { if (projection === mp) stopCapture(byUser = false) }
                }
            }, handler)

            val metrics = DisplayMetrics()
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val srcW = metrics.widthPixels
            val srcH = metrics.heightPixels
            // VirtualDisplay сразу масштабирует экран до размера высокого качества;
            // обычное качество получаем из него уменьшением при сжатии.
            val outH = minOf(StreamState.HQ_HEIGHT, srcH).let { it - it % 2 }
            val outW = (outH.toLong() * srcW / srcH).toInt().let { it - it % 2 }
            StreamState.sourceWidth = srcW
            StreamState.sourceHeight = srcH

            val ir = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
            reader = ir
            ir.setOnImageAvailableListener({ onImage(it) }, handler)
            StreamState.onFrameWanted = { handler.post { encodeLastFrame() } }

            virtualDisplay = mp.createVirtualDisplay(
                "orikly-stream", outW, outH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, handler
            )

            acquireLocks()
            http = HttpServer(this, BuildConfig.STREAM_KEY).also { it.start() }

            StreamState.errorText = null
            StreamState.status = StreamState.Status.RUNNING
            Log.i(TAG, "capture started ${srcW}x$srcH -> ${outW}x$outH")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun onImage(ir: ImageReader) {
        val image = try {
            ir.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val wanted = StreamState.wantsFrames(now)
            // Без зрителей только обновляем кадр в памяти раз в секунду (дёшево), без JPEG.
            val interval = if (wanted) 1000L / StreamState.TARGET_FPS else 1000L
            if (now - lastFrameAt < interval) return
            lastFrameAt = now

            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            // В строке буфера может быть хвост (rowStride > width * 4).
            val rowW = plane.rowStride / plane.pixelStride
            val src = rowBitmap?.takeIf { it.width == rowW && it.height == h }
                ?: Bitmap.createBitmap(rowW, h, Bitmap.Config.ARGB_8888).also { rowBitmap = it }
            plane.buffer.rewind()
            src.copyPixelsFromBuffer(plane.buffer)
            val frame = if (rowW == w) src else {
                val dst = outBitmap?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { outBitmap = it }
                android.graphics.Canvas(dst).drawBitmap(src, 0f, 0f, null)
                dst
            }
            lastFrame = frame
            if (wanted) encode(frame, now)
        } catch (e: Exception) {
            Log.w(TAG, "frame error", e)
        } finally {
            image.close()
        }
    }

    /** Вызывается на потоке захвата: сжать последний кадр сразу, не дожидаясь изменений на экране. */
    private fun encodeLastFrame() {
        val frame = lastFrame ?: return
        runCatching { encode(frame, SystemClock.elapsedRealtime()) }
            .onFailure { Log.w(TAG, "encode error", it) }
    }

    private fun encode(frame: Bitmap, now: Long) {
        val hq = StreamState.hqClients > 0
        val out = if (hq || frame.height <= StreamState.TARGET_HEIGHT) frame else {
            val h = StreamState.TARGET_HEIGHT
            val w = (h.toLong() * frame.width / frame.height).toInt().let { it - it % 2 }
            val dst = smallBitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { smallBitmap = it }
            android.graphics.Canvas(dst).drawBitmap(
                frame, null, android.graphics.Rect(0, 0, w, h), scalePaint
            )
            dst
        }
        StreamState.outWidth = out.width
        StreamState.outHeight = out.height
        jpegBuffer.reset()
        out.compress(
            Bitmap.CompressFormat.JPEG,
            if (hq) StreamState.HQ_JPEG_QUALITY else StreamState.JPEG_QUALITY,
            jpegBuffer
        )
        StreamState.publish(jpegBuffer.toByteArray())

        fpsWindowFrames++
        if (now - fpsWindowStart >= 2000) {
            StreamState.fps = fpsWindowFrames * 1000.0 / (now - fpsWindowStart)
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
    }

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
        http?.stop(); http = null
        StreamState.onFrameWanted = null
        lastFrame = null
        smallBitmap = null
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        worker?.quitSafely(); worker = null
        StreamState.clearFrame()
        StreamState.fps = 0.0
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
