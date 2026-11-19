package org.orikly.queststream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Отправка видео на сервер (WebSocket к Cloudflare Tunnel). Очки сами подключаются и держат соединение;
 * кадры шлют, только когда сервер просит ("want").
 *
 * Протокол: очки → сервер: text {"type":"hello","codec":"h264",...} / {"type":"status",...};
 *           binary = 1 байт типа (1 — ключевой кадр, с настройками видео; 2 — обычный) + кадр H.264 (Annex B).
 *           сервер → очки: {"type":"want","on","hq","fps","acks","key"} и {"type":"ack"} на каждый кадр.
 *
 * Кадры H.264 зависят друг от друга: выбросить один — картинка «рассыплется» до следующего ключевого.
 * Поэтому при плохом канале очки сначала снижают битрейт; если кадры всё же пришлось выбросить,
 * они сразу просят у кодировщика ключевой кадр и шлют уже с него.
 */
class RelayClient(private val ctx: Context, url: String, key: String) {

    private val fullUrl = url + (if ('?' in url) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8")
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS) // Cloudflare рвёт молчащие соединения через ~100 с
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var socket: WebSocket? = null

    // Подтверждения от ноутбука: сколько кадров в пути. Без этого при узком канале кадры копились
    // в Cloudflare и сетевых буферах, и задержка росла до секунд.
    @Volatile private var acksEnabled = false
    private val inFlight = AtomicInteger(0)
    private val sentTimes = ConcurrentLinkedQueue<Long>()
    @Volatile private var lastAckAt = 0L
    private var loop: Thread? = null

    // Отклики за последние RTT_WINDOW_MS: самый маленький — «чистое» время пути без очереди.
    private val rttSamples = ArrayDeque<Pair<Long, Long>>()

    // Сеть появилась (после сна / включения очков): подключаемся сразу, без паузы между попытками.
    private val netLock = Object()
    private var netAppeared = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        running = true
        watchNetwork()
        loop = thread(name = "relay", isDaemon = true) {
            try {
                run()
            } catch (e: InterruptedException) {
                // остановлено
            }
        }
    }

    fun stop() {
        running = false
        netCallback?.let { cb ->
            runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        netCallback = null
        socket?.close(1001, "stop")
        socket = null
        loop?.interrupt()
        loop = null
        setWant(on = false, hq = false, fps = StreamState.TARGET_FPS)
        StreamState.relayState = "off"
    }

    private fun watchNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(netLock) { netAppeared = true; netLock.notifyAll() }
            }
        }
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
            .onFailure { Log.w(TAG, "network callback", it) }
    }

    /** Что просит ноутбук: при изменении включаем / пересобираем кодировщик. */
    private fun setWant(on: Boolean, hq: Boolean, fps: Int) {
        val changed = on != StreamState.relayWant || hq != StreamState.relayHq || fps != StreamState.targetFps
        StreamState.relayWant = on
        StreamState.relayHq = hq
        StreamState.targetFps = fps
        if (changed) StreamState.onEncoderChange?.invoke()
    }

    private fun run() {
        var backoff = 2000L
        while (running) {
            StreamState.relayState = "connecting"
            synchronized(netLock) { netAppeared = false }
            val opened = CountDownLatch(1)
            val closed = CountDownLatch(1)
            var ok = false
            val ws = client.newWebSocket(Request.Builder().url(fullUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ok = true
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val msg = JSONObject(text)
                        when (msg.optString("type")) {
                            "ack" -> {
                                val now = SystemClock.elapsedRealtime()
                                lastAckAt = now
                                if (inFlight.decrementAndGet() < 0) inFlight.set(0)
                                sentTimes.poll()?.let { sentAt ->
                                    val rtt = now - sentAt
                                    StreamState.relayRttMs = (StreamState.relayRttMs * 3 + rtt) / 4
                                    noteRtt(now, rtt)
                                }
                            }
                            "want" -> {
                                acksEnabled = msg.optBoolean("acks")
                                val fps = msg.optInt("fps", 0)
                                setWant(
                                    on = msg.optBoolean("on"),
                                    hq = msg.optBoolean("hq"),
                                    fps = if (fps > 0) fps.coerceIn(1, StreamState.MAX_FPS) else StreamState.TARGET_FPS,
                                )
                                // Новый зритель: ему нужен ключевой кадр, чтобы начать показ.
                                if (msg.optBoolean("key")) StreamState.onKeyFrameRequest?.invoke()
                            }
                        }
                    }
                }

                // Бинарные сообщения от сервера (с 0.5.9): 1 байт типа + данные. 3 = голос учителя (PCM 16 кГц).
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > 1 && bytes[0].toInt() == VOICE_TYPE) {
                        VoicePlayer.feed(ctx, bytes.substring(1).toByteArray())
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.countDown(); closed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.i(TAG, "relay failure: ${t.message} ${response?.code}")
                    opened.countDown(); closed.countDown()
                }
            })
            socket = ws
            acksEnabled = false
            inFlight.set(0)
            sentTimes.clear()
            synchronized(rttSamples) { rttSamples.clear() }
            StreamState.relayRttMs = 0
            opened.await(20, TimeUnit.SECONDS)

            if (ok && closed.count > 0) {
                backoff = 2000L
                StreamState.relayState = "connected"
                ws.send(helloJson())
                pump(ws, closed)
            } else {
                ws.cancel()
            }

            socket = null
            setWant(on = false, hq = false, fps = StreamState.TARGET_FPS)
            if (!running) break
            StreamState.relayState = "reconnecting"
            val appeared = synchronized(netLock) {
                if (!netAppeared) netLock.wait(backoff)
                netAppeared
            }
            backoff = if (appeared) 2000L else minOf(backoff * 2, 30_000L)
        }
    }

    /** Пока соединение живо: статус раз в 2/10 с, кадры — по порядку, когда сервер просит. */
    private fun pump(ws: WebSocket, closed: CountDownLatch) {
        var lastStatus = 0L
        var sent = 0
        var dropped = 0
        var windowStart = SystemClock.elapsedRealtime()
        var calmSince = windowStart
        var waitKey = true // начинаем всегда с ключевого кадра
        var lastSentAt = windowStart
        lastAckAt = windowStart
        while (running && closed.count > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 2000) {
                if (adaptBitrate(dropped, now, calmSince)) calmSince = now
                StreamState.relaySentFps = sent * 1000.0 / (now - windowStart)
                sent = 0; dropped = 0; windowStart = now
            }
            if (now - lastStatus >= if (StreamState.relayWant) 2000 else 10_000) {
                ws.send(statusJson())
                lastStatus = now
            }
            if (!StreamState.relayWant) {
                StreamState.clearFrames()
                waitKey = true
                Thread.sleep(200)
                continue
            }
            val frame = StreamState.take(200) ?: continue
            if (waitKey && !frame.key) continue // без ключевого кадра браузеру не с чего начать
            waitKey = false

            // Окно подтверждений занято — ждём, но не дольше MAX_LAG_MS: иначе задержка копится.
            var tooLate = false
            while (acksEnabled && !canSend(SystemClock.elapsedRealtime()) && running && closed.count > 0) {
                val t = SystemClock.elapsedRealtime()
                if (t - lastAckAt > ACK_STUCK_MS && t - lastSentAt > ACK_STUCK_MS) {
                    inFlight.set(0); sentTimes.clear() // подтверждения потерялись — не зависаем навсегда
                    break
                }
                if (t - frame.atMs > MAX_LAG_MS) { tooLate = true; break }
                Thread.sleep(3)
            }
            if (tooLate || ws.queueSize() > MAX_BACKLOG_BYTES) {
                // Канал не успевает: выбрасываем накопленное и начинаем с нового ключевого кадра.
                dropped += 1
                StreamState.clearFrames()
                waitKey = true
                StreamState.onKeyFrameRequest?.invoke()
                continue
            }
            val t = SystemClock.elapsedRealtime()
            if (acksEnabled) {
                inFlight.incrementAndGet()
                sentTimes.add(t)
            }
            val msg = ByteArray(frame.data.size + 1)
            msg[0] = if (frame.key) 1 else 2
            System.arraycopy(frame.data, 0, msg, 1, frame.data.size)
            ws.send(msg.toByteString())
            lastSentAt = t
            sent++
        }
    }

    private fun noteRtt(now: Long, rtt: Long) = synchronized(rttSamples) {
        rttSamples.addLast(now to rtt)
        while (rttSamples.isNotEmpty() && now - rttSamples.first().first > RTT_WINDOW_MS) rttSamples.removeFirst()
    }

    private fun minRtt(now: Long): Long? = synchronized(rttSamples) {
        while (rttSamples.isNotEmpty() && now - rttSamples.first().first > RTT_WINDOW_MS) rttSamples.removeFirst()
        rttSamples.minOfOrNull { it.second }
    }

    /**
     * Можно ли отправить ещё кадр, пока не пришли подтверждения прошлых. До MIN_IN_FLIGHT кадров — всегда;
     * больше (до MAX_IN_FLIGHT) — только если самый старый неподтверждённый кадр в пути не дольше «чистого»
     * отклика + QUEUE_SLACK_MS (в канале нет очереди).
     */
    private fun canSend(now: Long): Boolean {
        val n = inFlight.get()
        if (n < MIN_IN_FLIGHT) return true
        if (n >= MAX_IN_FLIGHT) return false
        val min = minRtt(now) ?: return false
        val oldest = sentTimes.peek() ?: return true
        return now - oldest < min + QUEUE_SLACK_MS
    }

    /**
     * Битрейт под канал (частота кадров та же): кадры выбрасывались или отклик вырос заметно выше
     * «чистого» — битрейт ×0.75 (не ниже 30%); 6 с спокойно — ×1.15 (до 100%). true — если поменялся.
     */
    private fun adaptBitrate(dropped: Int, now: Long, calmSince: Long): Boolean {
        val min = minRtt(now)
        val congested = dropped > 0 || (min != null && StreamState.relayRttMs > min * 2 + 150)
        val old = StreamState.bitrateScale
        val new = when {
            congested -> maxOf(MIN_BITRATE_SCALE, old * 0.75)
            now - calmSince >= 6000 -> minOf(1.0, old * 1.15)
            else -> old
        }
        if (new != old) {
            StreamState.bitrateScale = new
            StreamState.onBitrateChange?.invoke()
            if (congested) Log.i(TAG, "network slow: bitrate ${StreamState.currentBitrate()} bps")
        }
        return congested || new != old
    }

    private fun helloJson() = JSONObject()
        .put("type", "hello")
        .put("id", DeviceInfo.id(ctx))
        .put("name", DeviceInfo.name(ctx))
        .put("model", Build.MODEL)
        .put("version", BuildConfig.VERSION_NAME)
        .put("codec", "h264")
        .toString()

    private fun statusJson(): String {
        val st = StreamState
        return JSONObject()
            .put("type", "status")
            .put("capturing", st.status == StreamState.Status.RUNNING)
            .put("codec", "h264")
            .put("fps", st.encFps)
            .put("targetFps", st.targetFps)
            .put("sentFps", st.relaySentFps)
            .put("rtt", st.relayRttMs)
            .put("bitrate", st.currentBitrate())
            .put("bitrateScale", Math.round(st.bitrateScale * 100))
            .put("level", if (st.bitrateScale < 0.99) 1 else 0) // для старой страницы: «качество снижено»
            .put("output", "${st.encWidth}x${st.encHeight}")
            .put("capture", "${st.encWidth}x${st.encHeight}")
            .put("source", "${st.sourceWidth}x${st.sourceHeight}")
            .put("sysFps", Math.round(st.encFps * 10) / 10.0)
            .put("hz", Math.round(st.displayHz))
            .put("os", Build.DISPLAY)
            .put("encoder", st.encoderName)
            .put("usageAccess", AppWatcher.hasAccess)
            .put("app", AppWatcher.label ?: JSONObject.NULL)
            .put("appPkg", AppWatcher.pkg ?: JSONObject.NULL)
            .put("appState", AppWatcher.state)
            .put("appEvent", AppWatcher.lastEvent)
            .put("serials", JSONObject(DeviceInfo.serials as Map<*, *>))
            .put("battery", DeviceInfo.battery(ctx) ?: JSONObject.NULL)
            .put("rssi", DeviceInfo.rssi(ctx) ?: JSONObject.NULL)
            .put("ip", DeviceInfo.ip(ctx) ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        private const val VOICE_TYPE = 3
        private const val TAG = "OriklyRelay"
        private const val MIN_IN_FLIGHT = 3
        private const val MAX_IN_FLIGHT = 16
        private const val RTT_WINDOW_MS = 30_000L
        private const val QUEUE_SLACK_MS = 60L
        private const val ACK_STUCK_MS = 5000L
        /** Кадр старше этого не шлём (вместе с накопленными) — начинаем с нового ключевого. */
        private const val MAX_LAG_MS = 700L
        private const val MAX_BACKLOG_BYTES = 256L * 1024
        private const val MIN_BITRATE_SCALE = 0.3
    }
}
