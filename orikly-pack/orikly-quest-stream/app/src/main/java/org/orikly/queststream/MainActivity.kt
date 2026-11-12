package org.orikly.queststream

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Экран на очках (по-испански). Разметка в коде, чтобы не тащить ресурсы. */
class MainActivity : Activity() {

    private lateinit var nameView: TextView
    private lateinit var addressView: TextView
    private lateinit var statusView: TextView
    private lateinit var diagView: TextView
    private lateinit var button: Button
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad / 3, 0, pad / 3)
        }

        nameView = text(26f, bold = true)
        addressView = text(20f)
        statusView = text(20f)
        diagView = text(16f).apply { alpha = 0.7f }
        button = Button(this).apply {
            textSize = 22f
            setOnClickListener { onButton() }
        }
        val hint = text(16f).apply {
            text = "En la ventana del sistema elige «Pantalla completa» " +
                "(no «Una sola aplicación») y pulsa «Empezar».\n" +
                "Después puedes salir de esta app: la transmisión sigue en segundo plano."
        }
        val keyView = text(14f).apply {
            text = "Clave de acceso: incluida en la app · versión ${BuildConfig.VERSION_NAME}"
            alpha = 0.6f
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(nameView)
            addView(addressView)
            addView(statusView)
            addView(diagView)
            addView(button)
            addView(hint)
            addView(keyView)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        handleAuto(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuto(intent)
    }

    /** Открыто автозапуском (после включения / сна): сразу окно подтверждения, без «Iniciar». */
    private fun handleAuto(i: Intent?) {
        if (i?.getBooleanExtra(AutoStart.EXTRA_AUTO, false) != true) return
        i.removeExtra(AutoStart.EXTRA_AUTO)
        AutoStart.clearNotification(this)
        if (StreamState.status == StreamState.Status.RUNNING) { finish(); return }
        autoMode = true
        requestCapture()
    }

    private var autoMode = false

    /** Окно подтверждения захвата. На Android 14 сразу «весь экран» — выбирать «Pantalla completa» не нужно. */
    private fun requestCapture() {
        // Каждый раз новое согласие: токен MediaProjection одноразовый.
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_CAPTURE)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun onButton() {
        if (StreamState.status == StreamState.Status.RUNNING) {
            CaptureService.stop(this)
        } else {
            autoMode = false
            requestCapture()
        }
    }

    @Deprecated("Activity API без androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            when {
                autoMode -> {}
                // Один раз на очках: «Доступ к истории использования» — чтобы учитель видел, в каком приложении ученик.
                !AppWatcher.checkAccess(this) && askOnce("usage") -> { openUsageAccessSettings(); return }
                // Один раз на очках: «Mostrar sobre otras apps» — чтобы после сна окно подтверждения открывалось само.
                !AutoStart.canOpenWindow(this) && askOnce("overlay") -> { openOverlaySettings(); return }
            }
            // Стрим идёт в фоне — закрываем окно, чтобы не мешало.
            finish()
            return
        } else {
            StreamState.status = StreamState.Status.ERROR
            StreamState.errorText = "Permiso denegado"
        }
        refresh()
    }

    private fun openUsageAccessSettings() {
        val action = android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
        val tries = listOf(
            Intent(action, android.net.Uri.parse("package:$packageName")), // сразу наше приложение (Android 10+)
            Intent(action),
        )
        for (i in tries) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        usageAccessMissing = true
    }

    private var usageAccessMissing = false
    private var overlayMissing = false

    /** Экран настроек каждого разрешения открываем сами только один раз на очках (если HMS его не даёт — не надоедаем). */
    private fun askOnce(what: String): Boolean {
        val prefs = getSharedPreferences("orikly", MODE_PRIVATE)
        if (prefs.getBoolean("asked_$what", false)) return false
        prefs.edit().putBoolean("asked_$what", true).apply()
        return true
    }

    private fun openOverlaySettings() {
        val action = android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        for (i in listOf(Intent(action, android.net.Uri.parse("package:$packageName")), Intent(action))) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        overlayMissing = true
    }

    private fun refresh() {
        nameView.text = DeviceInfo.name(this)
        val ip = DeviceInfo.ip(this)
        addressView.text = if (ip != null) "IP: $ip   Puerto: ${StreamState.PORT}" else "Sin conexión Wi-Fi"
        if (BuildConfig.RELAY_URL.isNotBlank()) {
            val relay = when (StreamState.relayState) {
                "connected" -> if (StreamState.relayWant) "conectado · enviando" else "conectado"
                "connecting", "reconnecting" -> "conectando…"
                else -> "inactivo"
            }
            addressView.append("\nServidor: $relay")
        }
        val st = StreamState
        statusView.text = when (st.status) {
            StreamState.Status.IDLE -> "Estado: detenido"
            StreamState.Status.RUNNING -> "Estado: transmitiendo · %.0f fps · %dx%d · espectadores: %d"
                .format(st.fps, st.outWidth, st.outHeight, st.streamClients)
            StreamState.Status.STOPPED -> "Estado: detenido por el sistema. Pulsa «Iniciar» de nuevo."
            StreamState.Status.ERROR -> "Estado: error — ${st.errorText ?: "desconocido"}"
        }
        // Диагностика: «sistema» — кадры/с от самой системы, «enviados» — кадры/с, ушедшие на ноутбук.
        diagView.text = if (st.status == StreamState.Status.RUNNING)
            "Diagnóstico: sistema %.1f fps · enviados %.1f de %d fps · captura %dx%d · pantalla %dx%d %d Hz"
                .format(st.systemFps, st.relaySentFps, st.targetFps, st.captureWidth, st.captureHeight,
                    st.sourceWidth, st.sourceHeight, Math.round(st.displayHz))
        else ""
        val app = AppWatcher.label?.let { "$it (${AppWatcher.state}, último evento: ${AppWatcher.lastEvent})" } ?: "—"
        diagView.append(
            (if (diagView.text.isEmpty()) "" else "\n") +
                when {
                    AppWatcher.checkAccess(this) -> "Aplicación: $app"
                    usageAccessMissing -> "Acceso de uso: no disponible en este dispositivo"
                    else -> "Acceso de uso: no concedido (se pedirá al pulsar «Iniciar»)"
                } +
                "\nAutoinicio tras reposo: " +
                (if (AutoStart.canOpenWindow(this)) "ventana automática" else "notificación (permiso «Mostrar sobre otras apps» no concedido)") +
                "\nN.º de serie: " + DeviceInfo.serials.entries.joinToString(" · ") { "${it.key}=${it.value}" }
        )
        button.text = if (st.status == StreamState.Status.RUNNING) "Detener transmisión" else "Iniciar transmisión"
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
    }
}
