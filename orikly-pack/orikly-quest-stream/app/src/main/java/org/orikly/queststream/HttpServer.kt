package org.orikly.queststream

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Минимальный HTTP/1.1 сервер без зависимостей: поток на приём + поток на клиента.
 * Клиентов мало (ноутбук наблюдателя), поэтому этого достаточно.
 */
class HttpServer(private val ctx: Context, private val key: String) {

    private var server: ServerSocket? = null
    private val streamClients = AtomicInteger(0)

    fun start() {
        val socket = ServerSocket(StreamState.PORT).apply { reuseAddress = true }
        server = socket
        thread(name = "http-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "http-client", isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = 5000
                s.tcpNoDelay = true
                val requestLine = readRequestHead(s) ?: return
                val parts = requestLine.split(" ")
                if (parts.size >= 2 && parts[0] == "OPTIONS") {
                    // Preflight Chrome (Private/Local Network Access) со страницы file:// на ноутбуке.
                    preflight(s.getOutputStream())
                    return
                }
                if (parts.size < 2 || parts[0] != "GET") {
                    sendText(s.getOutputStream(), 405, "text/plain", "Method Not Allowed")
                    return
                }
                val (path, query) = parseTarget(parts[1])
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                if (query["key"] != key) {
                    sendText(out, 403, "text/plain", "Forbidden")
                    return
                }
                when (path) {
                    "/stream" -> stream(s, out)
                    "/snapshot.jpg" -> snapshot(out)
                    "/status" -> sendText(out, 200, "application/json", statusJson())
                    else -> sendText(out, 404, "text/plain", "Not Found")
                }
            }
        } catch (e: IOException) {
            // Клиент отвалился — это нормально, сервис не трогаем.
        } catch (e: Exception) {
            Log.w(TAG, "client error", e)
        }
    }

    /** Читает заголовки запроса до пустой строки, возвращает первую строку. */
    private fun readRequestHead(s: Socket): String? {
        val input = s.getInputStream()
        val sb = StringBuilder()
        var first: String? = null
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (first == null) first = line
                else if (line.isEmpty()) return first
            } else {
                sb.append(b.toChar())
                if (sb.length > 8192) return null
            }
        }
    }

    private fun parseTarget(target: String): Pair<String, Map<String, String>> {
        val q = target.indexOf('?')
        if (q < 0) return target to emptyMap()
        val query = target.substring(q + 1).split("&").mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null
            else URLDecoder.decode(it.substring(0, eq), "UTF-8") to
                URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }.toMap()
        return target.substring(0, q) to query
    }

    private fun stream(s: Socket, out: OutputStream) {
        if (streamClients.incrementAndGet() > StreamState.MAX_STREAM_CLIENTS) {
            streamClients.decrementAndGet()
            sendText(out, 503, "text/plain", "Too many viewers")
            return
        }
        StreamState.streamClients = streamClients.get()
        try {
            s.soTimeout = 0
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n" +
                    "--$BOUNDARY\r\n").toByteArray()
            )
            out.flush()
            // Старые кадры не шлём: просим свежий, он придёт сразу, даже если экран статичен.
            var lastSeq = StreamState.latest().first
            StreamState.requestFrames(0)
            while (StreamState.status == StreamState.Status.RUNNING) {
                // Если экран статичен, новых кадров нет — раз в секунду повторяем последний,
                // чтобы браузер видел живое соединение, а мёртвые сокеты быстро отваливались.
                val (seq, jpeg) = StreamState.awaitNewer(lastSeq, 1000)
                if (jpeg == null) continue
                lastSeq = seq
                out.write(
                    ("Content-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n").toByteArray()
                )
                out.write(jpeg)
                // Граница сразу после кадра: Chrome показывает кадр без ожидания следующего.
                out.write("\r\n--$BOUNDARY\r\n".toByteArray())
                out.flush()
            }
        } finally {
            StreamState.streamClients = streamClients.decrementAndGet()
        }
    }

    private fun snapshot(out: OutputStream) {
        val before = StreamState.latest().first
        StreamState.requestFrames(1500)
        val jpeg = StreamState.awaitNewer(before, 1500).second
        if (jpeg == null) {
            sendText(out, 503, "text/plain", "No frame yet")
            return
        }
        send(out, 200, "image/jpeg", jpeg)
    }

    private fun statusJson(): String {
        val st = StreamState
        fun str(v: String?) = if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return "{" +
            "\"name\":${str(DeviceInfo.name(ctx))}," +
            "\"model\":${str(Build.MODEL)}," +
            "\"ip\":${str(DeviceInfo.ip(ctx))}," +
            "\"port\":${StreamState.PORT}," +
            "\"capturing\":${st.status == StreamState.Status.RUNNING}," +
            "\"status\":${str(st.status.name)}," +
            "\"error\":${str(st.errorText)}," +
            "\"fps\":${"%.1f".format(java.util.Locale.US, st.fps)}," +
            "\"source\":\"${st.sourceWidth}x${st.sourceHeight}\"," +
            "\"output\":\"${st.outWidth}x${st.outHeight}\"," +
            "\"viewers\":${st.streamClients}," +
            "\"battery\":${DeviceInfo.battery(ctx)}," +
            "\"rssi\":${DeviceInfo.rssi(ctx)}," +
            "\"version\":${str(BuildConfig.VERSION_NAME)}" +
            "}"
    }

    private fun preflight(out: OutputStream) {
        out.write(
            ("HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Allow-Private-Network: true\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.flush()
    }

    private fun sendText(out: OutputStream, code: Int, type: String, body: String) =
        send(out, code, "$type; charset=utf-8", body.toByteArray())

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; else -> "Service Unavailable"
        }
        out.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "OriklyHttp"
        private const val BOUNDARY = "oriklyframe"
    }
}
