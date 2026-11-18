package org.orikly.queststream

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Блокировка приложений паролем (0.5.5): магазин Meta, браузер и др. из [LOCKED].
 * Ученик открывает что хочет, но запретное приложение просит пароль учителя. Верный пароль открывает
 * именно это приложение — до его закрытия или до сна очков; потом снова пароль.
 *
 * Как замечаем: (1) история использования — опрос раз в [POLL_MS] в WakeService (ADB: GET_USAGE_STATS);
 * (2) служба специальных возможностей [LockAccessibilityService], если включена ADB-командой: видит любое
 * нажатие внутри запретного приложения и закрывает его кнопкой «Назад» за ученика. Без неё на Quest панель
 * магазина может остаться рабочей рядом с окном пароля (на Quest несколько панелей активны одновременно).
 * Окно пароля открывается из фона — нужен ADB SYSTEM_ALERT_WINDOW.
 *
 * Пароль задаётся при сборке (секрет GitHub APP_LOCK_PASSWORD); в APK лежит только его хэш SHA-256.
 */
object AppLock {
    private const val TAG = "OriklyLock"

    /** Запретные приложения (имена пакетов). Магазин и браузер Meta — проверено Paul на очках (dumpsys). */
    val LOCKED: Set<String> = setOf(
        "com.oculus.store",
        "com.oculus.browser",
    )

    const val POLL_MS = 400L
    private const val SHOW_GAP_MS = 800L
    private const val BACK_GAP_MS = 500L
    private const val ACTIVITY_DESTROYED = 24 // скрытая константа системы (Android 10+)

    /** Соль хэша — та же строка, что в app/build.gradle.kts. */
    const val SALT = "org.orikly.queststream:"

    fun hasPassword() = BuildConfig.LOCK_PASSWORD_SHA256.isNotBlank()
    fun configured() = hasPassword() && LOCKED.isNotEmpty()
    fun passwordOk(p: String) = sha256(SALT + p).equals(BuildConfig.LOCK_PASSWORD_SHA256, ignoreCase = true)

    fun sha256(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Служба спец. возможностей подключена — тогда запретное приложение закрываем сами. */
    @Volatile var accessibility: LockAccessibilityService? = null

    private val main = Handler(Looper.getMainLooper())
    /** Разблокированные паролем приложения → их открытые окна (закрылись все — снова под паролем). */
    private val unlocked = HashMap<String, MutableSet<String>>()
    private var lastEventTime = 0L
    private var lastShownAt = 0L
    private var lastBackAt = 0L

    @Synchronized fun isUnlocked(p: String) = unlocked.containsKey(p)
    @Synchronized fun unlock(p: String) { unlocked[p] = mutableSetOf() }
    /** Сон очков: всё снова под паролем. */
    @Synchronized fun relockAll() { unlocked.clear() }

    /** Ученик открыл запретное приложение или нажал в нём что-то. Любой поток. */
    fun onAppSeen(ctx: Context, p: String, interaction: Boolean) {
        if (!configured() || p !in LOCKED || isUnlocked(p)) return
        val app = ctx.applicationContext
        main.post { block(app, p, interaction) }
    }

    private fun block(ctx: Context, p: String, interaction: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val svc = accessibility
        // «Назад» за ученика — только если сейчас в фокусе запретное приложение, а не наше окно пароля.
        if (svc != null && interaction && !LockActivity.focused && now - lastBackAt >= BACK_GAP_MS) {
            lastBackAt = now
            svc.back()
        }
        if (now - lastShownAt < SHOW_GAP_MS) return
        lastShownAt = now
        Log.i(TAG, "blocked: $p")
        // Окно пароля чуть позже «Назад», чтобы нажатие ушло запретному приложению, а не нашему окну.
        main.postDelayed({ LockActivity.show(ctx, p) }, if (svc != null) 300L else 0L)
    }

    /** Опрос истории использования (WakeService, раз в POLL_MS). */
    fun poll(ctx: Context) {
        if (!configured() || !AppWatcher.checkAccess(ctx)) return
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val from = if (lastEventTime == 0L) now - 10_000L else lastEventTime + 1
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            lastEventTime = maxOf(lastEventTime, e.timeStamp)
            val p = e.packageName ?: continue
            if (p !in LOCKED) continue
            val activity = e.className ?: ""
            synchronized(this) {
                val open = unlocked[p]
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> if (open != null) open += activity else onAppSeen(ctx, p, interaction = false)
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> open?.add(activity)
                    ACTIVITY_DESTROYED -> if (open != null) {
                        open -= activity
                        if (open.isEmpty()) {
                            unlocked -= p
                            Log.i(TAG, "closed, locked again: $p")
                        }
                    }
                }
            }
        }
    }

    fun label(ctx: Context, p: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
    }.getOrDefault(p)
}

/** Окно «Введите пароль» поверх запретного приложения (по-испански, разметка в коде). */
class LockActivity : Activity() {
    private var pkg = ""
    private var wrong = 0
    private lateinit var message: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var open: Button
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad / 3, 0, pad / 3)
        }
        val title = text(26f, bold = true).apply { text = "Aplicación bloqueada" }
        message = text(20f)
        input = EditText(this).apply {
            textSize = 22f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Contraseña del profesor"
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> tryOpen(); true }
        }
        status = text(18f).apply { setTextColor(Color.rgb(0xE5, 0x48, 0x4D)) }
        open = Button(this).apply { textSize = 22f; text = "Abrir"; setOnClickListener { tryOpen() } }
        val cancel = Button(this).apply { textSize = 22f; text = "Cancelar"; setOnClickListener { cancel() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(message); addView(input); addView(status); addView(open); addView(cancel)
        })
        setPkg(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setPkg(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
    }

    override fun onDestroy() {
        focused = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setPkg(i: Intent?) {
        pkg = i?.getStringExtra(EXTRA_PKG) ?: pkg
        message.text = "«${AppLock.label(this, pkg)}» necesita la contraseña del profesor."
        input.setText("")
        if (open.isEnabled) status.text = ""
    }

    private fun tryOpen() {
        if (!open.isEnabled) return
        if (AppLock.passwordOk(input.text.toString())) {
            AppLock.unlock(pkg)
            Log.i("OriklyLock", "unlocked: $pkg")
            // Открыть приложение снова (если служба спец. возможностей его закрыла) или вернуть его вперёд.
            packageManager.getLaunchIntentForPackage(pkg)?.let { li ->
                runCatching { startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            finish()
            return
        }
        wrong++
        input.setText("")
        if (wrong >= 3) {
            // Три ошибки подряд — пауза 30 с: подбирать пароль долго.
            wrong = 0
            open.isEnabled = false
            input.isEnabled = false
            status.text = "Contraseña incorrecta. Espera 30 segundos."
            ui.postDelayed({ open.isEnabled = true; input.isEnabled = true; status.text = "" }, 30_000L)
        } else {
            status.text = "Contraseña incorrecta"
        }
    }

    private fun cancel() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    @Deprecated("Activity API без androidx")
    override fun onBackPressed() = cancel()

    companion object {
        private const val EXTRA_PKG = "pkg"
        /** Наше окно пароля сейчас в фокусе («Назад» за ученика тогда не нажимаем — оно закрыло бы его). */
        @Volatile var focused = false

        fun show(ctx: Context, pkg: String) {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, LockActivity::class.java).putExtra(EXTRA_PKG, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            }.onFailure { Log.w("OriklyLock", "cannot open lock window", it) }
        }
    }
}

/**
 * Служба специальных возможностей: получает события только от запретных приложений (магазин, браузер)
 * и при нажатии внутри них закрывает их кнопкой «Назад» + окно пароля. Включается ADB-командой
 * (settings put secure enabled_accessibility_services …), на очках нажимать ничего не нужно.
 */
class LockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = AppLock.LOCKED.toTypedArray() // остальные приложения (игры) сюда не попадают
        }
        AppLock.accessibility = this
        Log.i("OriklyLock", "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = event?.packageName?.toString() ?: return
        AppLock.onAppSeen(this, p, interaction = true)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (AppLock.accessibility === this) AppLock.accessibility = null
        return super.onUnbind(intent)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
}
