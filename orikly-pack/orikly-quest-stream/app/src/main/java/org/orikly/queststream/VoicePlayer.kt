package org.orikly.queststream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Голос учителя (с 0.5.9): сервер шлёт куски PCM 16 бит, моно, 16 кГц (по 40 мс) — проигрываем сразу.
 *
 * - Пока звучит голос, просим у Android «приглушить остальных» (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK):
 *   игры, которые соблюдают правила звука Android, становятся тише. Часть VR-игр их не соблюдает —
 *   тогда голос просто поверх игры.
 * - Голос громче: LoudnessEnhancer +8 дБ. Громкость — та, что выставлена в шлеме (поток «музыка»).
 * - Голос не копится: в очереди не больше ~0,4 с, лишнее выбрасываем. Перед стартом — запас 120 мс.
 * - Тишина 0,8 с → звук остановлен, приглушение снято (игра снова громкая).
 */
object VoicePlayer {
    private const val TAG = "OriklyVoice"
    private const val RATE = 16_000
    private const val PREBUFFER_MS = 120
    private const val MAX_QUEUE_MS = 400
    private const val IDLE_MS = 800L
    private const val GAIN_MB = 800 // +8 дБ

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger(0)
    @Volatile private var started = false
    private lateinit var appCtx: Context

    /** Для экрана очков: когда последний раз звучал голос. */
    @Volatile var lastVoiceAt = 0L

    /** Кусок голоса от сервера (поток OkHttp — не блокируем). */
    fun feed(ctx: Context, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(this) {
            if (!started) {
                appCtx = ctx.applicationContext
                started = true
                thread(name = "voice", isDaemon = true) { loop() }
            }
        }
        // Не копим задержку: если очередь длиннее MAX_QUEUE_MS — выбрасываем старое.
        while (queuedBytes.get() > bytesFor(MAX_QUEUE_MS)) {
            val old = queue.poll() ?: break
            queuedBytes.addAndGet(-old.size)
        }
        queue.offer(pcm)
        queuedBytes.addAndGet(pcm.size)
        lastVoiceAt = SystemClock.elapsedRealtime()
    }

    private fun bytesFor(ms: Int) = RATE * 2 * ms / 1000

    private fun loop() {
        var track: AudioTrack? = null
        var enhancer: LoudnessEnhancer? = null
        var focus: AudioFocusRequest? = null
        val am = appCtx.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) // громкость как у музыки, приглушает остальных
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        while (true) {
            val chunk = queue.poll(IDLE_MS, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                // Тишина: остановить звук и снять приглушение игры.
                if (track != null) {
                    runCatching { track.pause(); track.flush(); track.release() }
                    runCatching { enhancer?.release() }
                    focus?.let { f -> runCatching { am.abandonAudioFocusRequest(f) } }
                    track = null; enhancer = null; focus = null
                    Log.i(TAG, "voice stopped")
                }
                continue
            }
            queuedBytes.addAndGet(-chunk.size)
            if (track == null) {
                // Запас перед стартом, чтобы не было щелчков от неровной доставки.
                val deadline = SystemClock.elapsedRealtime() + PREBUFFER_MS + 200
                val first = mutableListOf(chunk)
                var have = chunk.size
                while (have < bytesFor(PREBUFFER_MS) && SystemClock.elapsedRealtime() < deadline) {
                    val more = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    queuedBytes.addAndGet(-more.size)
                    first += more; have += more.size
                }
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                runCatching { am.requestAudioFocus(focus!!) }
                val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(
                            AudioFormat.Builder().setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                        )
                        .setBufferSizeInBytes(maxOf(minBuf, bytesFor(200)))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }.onFailure { Log.w(TAG, "audio track", it) }.getOrNull()
                if (track == null) {
                    focus?.let { f -> runCatching { am.abandonAudioFocusRequest(f) } }
                    focus = null
                    continue
                }
                enhancer = runCatching {
                    LoudnessEnhancer(track.audioSessionId).apply { setTargetGain(GAIN_MB); enabled = true }
                }.onFailure { Log.w(TAG, "loudness enhancer", it) }.getOrNull()
                track.play()
                Log.i(TAG, "voice started")
                for (c in first) track.write(c, 0, c.size)
                continue
            }
            track.write(chunk, 0, chunk.size) // блокирует, пока звук не уйдёт в буфер — это и есть темп
        }
    }
}
