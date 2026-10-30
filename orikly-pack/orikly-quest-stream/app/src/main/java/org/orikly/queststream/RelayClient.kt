package org.orikly.queststream

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Отправка картинки на сервер ноутбука через интернет (WebSocket к Cloudflare Tunnel).
 * Очки сами подключаются и держат соединение; кадры шлют, только когда сервер просит ("want").
 *
 * Протокол: очки → сервер: text {"type":"hello"|"status",...}, binary = JPEG.
 *           сервер → очки: text {"type":"want","on":bool,"hq":bool}.
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

    // Подтверждения от ноутбука: в пути не больше MAX_IN_FLIGHT кадров. Без этого при узком интернете
    // ноутбука кадры копились в Cloudflare и сетевых буферах, и задержка росла до 5–7 с.
    @Volatile private var acksEnabled = false
    private val inFlight = AtomicInteger(0)
    private val sentTimes = ConcurrentLinkedQueue<Long>()
    @Volatile private var lastAckAt = 0L
    private var loop: Thread? = null

    fun start() {
        running = true
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
        socket?.close(1001, "stop")
        socket = null
        loop?.interrupt()
        loop = null
        StreamState.relayWant = false
        StreamState.relayHq = false
        StreamState.relayState = "off"
    }

    private fun run() {
        var backoff = 2000L
        while (running) {
            StreamState.relayState = "connecting"
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
                        if (msg.optString("type") == "ack") {
                            val now = SystemClock.elapsedRealtime()
                            lastAckAt = now
                            if (inFlight.decrementAndGet() < 0) inFlight.set(0)
                            sentTimes.poll()?.let { sentAt ->
                                val rtt = now - sentAt
                                StreamState.relayRttMs = (StreamState.relayRttMs * 3 + rtt) / 4
                            }
                        } else if (msg.optString("type") == "want") {
                            acksEnabled = msg.optBoolean("acks") // сервер старее v0.3.3 подтверждений не шлёт
                            val on = msg.optBoolean("on")
                            StreamState.relayHq = msg.optBoolean("hq")
                            val wasOn = StreamState.relayWant
                            StreamState.relayWant = on
                            if (on && !wasOn) StreamState.requestFrames(0)
                            else if (on) StreamState.onFrameWanted?.invoke() // качество сменилось — свежий кадр сразу
                        }
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
            StreamState.relayWant = false
            StreamState.relayHq = false
            StreamState.relayLevel = 0
            if (!running) break
            StreamState.relayState = "reconnecting"
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 30_000L)
        }
    }

    /** Пока соединение живо: статус раз в 10 с, кадры — когда сервер просит. */
    private fun pump(ws: WebSocket, closed: CountDownLatch) {
        var lastSeq = StreamState.latest().first
        var lastStatus = 0L
        var sent = 0
        var dropped = 0
        var windowStart = SystemClock.elapsedRealtime()
        var calmSince = windowStart
        var waitingSeq = -1L
        var lastSentAt = windowStart
        lastAckAt = windowStart
        StreamState.relayLevel = 0
        while (running && closed.count > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 2000) {
                adapt(sent, dropped, now, calmSince).let { if (it) calmSince = now }
                StreamState.relaySentFps = sent * 1000.0 / (now - windowStart)
                sent = 0; dropped = 0; windowStart = now
            }
            // Пока идёт картинка — статус чаще, чтобы на ноутбуке было видно задержку канала.
            if (now - lastStatus >= if (StreamState.relayWant) 2000 else 10_000) {
                ws.send(statusJson())
                lastStatus = now
            }
            if (!StreamState.relayWant) {
                Thread.sleep(200)
                continue
            }
            val (seq, jpeg) = StreamState.awaitNewer(lastSeq, 500)
            if (jpeg == null || seq == lastSeq) continue
            if (acksEnabled && inFlight.get() >= MAX_IN_FLIGHT) {
                if (now - lastAckAt > ACK_STUCK_MS && now - lastSentAt > ACK_STUCK_MS) {
                    inFlight.set(0); sentTimes.clear() // подтверждения потерялись — не зависаем навсегда
                } else {
                    // Окно занято: этот кадр не шлём сейчас, а отправим самый свежий, как только придёт подтверждение.
                    if (seq != waitingSeq) { dropped++; waitingSeq = seq }
                    Thread.sleep(5)
                    continue
                }
            }
            lastSeq = seq
            // Не копим очередь и у себя: если прошлый кадр ещё не ушёл из очков, этот выбрасываем.
            if (ws.queueSize() > maxOf(MIN_BACKLOG_BYTES, jpeg.size.toLong())) {
                dropped++
            } else {
                if (acksEnabled) {
                    inFlight.incrementAndGet()
                    sentTimes.add(now)
                }
                ws.send(jpeg.toByteString())
                lastSentAt = now
                sent++
            }
        }
    }

    /**
     * Держим 10 кадров/с: если за 2 с выброшено >10% кадров — ступень качества ниже;
     * если 6 с без потерь — ступень выше. Возвращает true, если ступень сменилась.
     */
    private fun adapt(sent: Int, dropped: Int, now: Long, calmSince: Long): Boolean {
        val total = sent + dropped
        val max = StreamState.NORMAL_LADDER.size - 1
        val level = StreamState.relayLevel
        if (total > 0 && dropped * 10 > total && level < max) {
            StreamState.relayLevel = level + 1
            Log.i(TAG, "network slow: quality level ${level + 1}")
            return true
        }
        if (dropped == 0 && now - calmSince >= 6000 && level > 0) {
            StreamState.relayLevel = level - 1
            return true
        }
        return dropped > 0
    }

    private fun helloJson() = JSONObject()
        .put("type", "hello")
        .put("id", DeviceInfo.id(ctx))
        .put("name", DeviceInfo.name(ctx))
        .put("model", Build.MODEL)
        .put("version", BuildConfig.VERSION_NAME)
        .toString()

    private fun statusJson(): String {
        val st = StreamState
        return JSONObject()
            .put("type", "status")
            .put("capturing", st.status == StreamState.Status.RUNNING)
            .put("fps", st.fps)
            .put("sentFps", st.relaySentFps)
            .put("level", st.relayLevel)
            .put("rtt", st.relayRttMs)
            .put("output", "${st.outWidth}x${st.outHeight}")
            .put("source", "${st.sourceWidth}x${st.sourceHeight}")
            .put("battery", DeviceInfo.battery(ctx) ?: JSONObject.NULL)
            .put("rssi", DeviceInfo.rssi(ctx) ?: JSONObject.NULL)
            .put("ip", DeviceInfo.ip(ctx) ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        private const val TAG = "OriklyRelay"
        private const val MIN_BACKLOG_BYTES = 32L * 1024
        private const val MAX_IN_FLIGHT = 3 // ~10 кадров/с даже при отклике канала до ~300 мс
        private const val ACK_STUCK_MS = 5000L
    }
}
