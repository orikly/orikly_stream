package org.orikly.queststream

/** Общее состояние между сервисом, HTTP-сервером и экраном приложения. */
object StreamState {
    const val PORT = 8080
    const val TARGET_HEIGHT = 480
    const val TARGET_FPS = 10
    const val JPEG_QUALITY = 60
    /** Высокое качество — для разворота одних очков на весь экран (?hq=1). */
    const val HQ_HEIGHT = 960
    const val HQ_JPEG_QUALITY = 80
    const val MAX_STREAM_CLIENTS = 4

    enum class Status { IDLE, RUNNING, STOPPED, ERROR }

    @Volatile var status: Status = Status.IDLE
    @Volatile var errorText: String? = null
    @Volatile var sourceWidth = 0
    @Volatile var sourceHeight = 0
    @Volatile var outWidth = 0
    @Volatile var outHeight = 0
    @Volatile var fps = 0.0
    @Volatile var streamClients = 0
    @Volatile var hqClients = 0

    /** Сервер ноутбука (через интернет) просит картинку / высокое качество. */
    @Volatile var relayWant = false
    @Volatile var relayHq = false
    @Volatile var relayState = "off"

    fun hqWanted() = hqClients > 0 || relayHq

    /** Пока никто не смотрит, JPEG не сжимаем: захват держим, нагрузки почти нет. */
    @Volatile var encodeUntil = 0L
    @Volatile var onFrameWanted: (() -> Unit)? = null

    fun wantsFrames(now: Long) = streamClients > 0 || relayWant || now < encodeUntil

    /** Просит свежий кадр прямо сейчас (даже если экран статичен и система кадров не шлёт). */
    fun requestFrames(forMs: Long) {
        encodeUntil = maxOf(encodeUntil, android.os.SystemClock.elapsedRealtime() + forMs)
        onFrameWanted?.invoke()
    }

    private val lock = Object()
    private var frame: ByteArray? = null
    private var seq = 0L

    fun publish(jpeg: ByteArray) = synchronized(lock) {
        frame = jpeg
        seq++
        lock.notifyAll()
    }

    fun latest(): Pair<Long, ByteArray?> = synchronized(lock) { seq to frame }

    /** Ждёт кадр новее [afterSeq] не дольше [timeoutMs]; по таймауту отдаёт последний. */
    fun awaitNewer(afterSeq: Long, timeoutMs: Long): Pair<Long, ByteArray?> = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (seq <= afterSeq) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
        seq to frame
    }

    fun clearFrame() = synchronized(lock) {
        frame = null
        lock.notifyAll()
    }
}
