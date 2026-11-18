package org.orikly.queststream

/** Общее состояние между сервисом захвата, связью с сервером и экраном приложения. */
object StreamState {
    /** Частота по умолчанию; ноутбук может попросить другую (поле "fps" в "want"), не больше MAX_FPS. */
    const val TARGET_FPS = 15
    const val MAX_FPS = 30
    @Volatile var targetFps = TARGET_FPS

    /**
     * Видео H.264 (с 0.5.0): аппаратный кодировщик очков. Сетка — 640×360, 0,5 Мбит/с;
     * развёрнутые очки — 1280×720, 2,5 Мбит/с (решения Paul). Битрейт ровный в любом приложении.
     */
    const val GRID_WIDTH = 640
    const val GRID_HEIGHT = 360
    const val GRID_BITRATE = 500_000
    const val HQ_WIDTH = 1280
    const val HQ_HEIGHT = 720
    const val HQ_BITRATE = 2_500_000
    /** Ключевой кадр (полная картинка) не реже, чем раз в столько секунд. */
    const val KEY_INTERVAL_S = 2

    enum class Status { IDLE, RUNNING, STOPPED, ERROR }

    @Volatile var status: Status = Status.IDLE
    @Volatile var errorText: String? = null
    @Volatile var sourceWidth = 0
    @Volatile var sourceHeight = 0
    @Volatile var encWidth = 0
    @Volatile var encHeight = 0
    /** Сколько кадров в секунду выдаёт кодировщик (до отправки). */
    @Volatile var encFps = 0.0
    @Volatile var displayHz = 0f
    @Volatile var encoderName = ""

    /** Сервер ноутбука просит картинку / высокое качество. */
    @Volatile var relayWant = false
    @Volatile var relayHq = false
    @Volatile var relayState = "off"
    @Volatile var relaySentFps = 0.0
    /** Время «кадр ушёл → ноутбук подтвердил», мс. Растёт — значит, канал на пределе. */
    @Volatile var relayRttMs = 0L

    /** Доля битрейта (0.3..1): при плохом канале очки сами снижают битрейт, частота кадров та же. */
    @Volatile var bitrateScale = 1.0
    fun baseBitrate() = if (relayHq) HQ_BITRATE else GRID_BITRATE
    fun currentBitrate() = (baseBitrate() * bitrateScale).toInt()

    /** Сервис захвата подписывается: пересобрать кодировщик (размер/частота/вкл-выкл), ключевой кадр, битрейт. */
    @Volatile var onEncoderChange: (() -> Unit)? = null
    @Volatile var onKeyFrameRequest: (() -> Unit)? = null
    @Volatile var onBitrateChange: (() -> Unit)? = null

    /** Один закодированный кадр. Ключевой содержит в начале настройки видео (SPS/PPS) — с него можно начать показ. */
    class Frame(val key: Boolean, val data: ByteArray, val atMs: Long)

    private val lock = Object()
    private val frames = ArrayDeque<Frame>()

    fun publish(f: Frame) = synchronized(lock) {
        // Отправка не успевает совсем (кадры не забирают): не копим, начнём заново с ключевого.
        if (frames.size >= 120) frames.clear()
        frames.addLast(f)
        lock.notifyAll()
    }

    /** Следующий кадр по порядку; ждёт не дольше [timeoutMs]. */
    fun take(timeoutMs: Long): Frame? = synchronized(lock) {
        if (frames.isEmpty()) lock.wait(timeoutMs)
        frames.removeFirstOrNull()
    }

    fun clearFrames() = synchronized(lock) { frames.clear() }
}
