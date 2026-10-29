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
            addView(button)
            addView(hint)
            addView(keyView)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
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
            // Каждый раз новое согласие: токен MediaProjection одноразовый.
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
    }

    @Deprecated("Activity API без androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
        } else {
            StreamState.status = StreamState.Status.ERROR
            StreamState.errorText = "Permiso denegado"
        }
        refresh()
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
        button.text = if (st.status == StreamState.Status.RUNNING) "Detener transmisión" else "Iniciar transmisión"
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
    }
}
