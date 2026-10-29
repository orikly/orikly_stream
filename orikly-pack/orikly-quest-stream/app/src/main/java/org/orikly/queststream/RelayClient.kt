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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                        if (msg.optString("type") == "want") {
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
        StreamState.relayLevel = 0
        while (running && closed.count > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 2000) {
                adapt(sent, dropped, now, calmSince).let { if (it) calmSince = now }
                StreamState.relaySentFps = sent * 1000.0 / (now - windowStart)
                sent = 0; dropped = 0; windowStart = now
            }
            if (now - lastStatus >= 10_000) {
                ws.send(statusJson())
                lastStatus = now
            }
            if (!StreamState.relayWant) {
                Thread.sleep(200)
                continue
            }
            val (seq, jpeg) = StreamState.awaitNewer(lastSeq, 500)
            if (jpeg == null || seq == lastSeq) continue
            lastSeq = seq
            // Не копим очередь: если прошлый кадр ещё не ушёл, этот выбрасываем (иначе растёт задержка).
            if (ws.queueSize() > maxOf(MIN_BACKLOG_BYTES, jpeg.size.toLong())) {
                dropped++
            } else {
                ws.send(jpeg.toByteString())
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
    }
}
