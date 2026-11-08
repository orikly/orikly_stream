package org.orikly.queststream

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * В каком приложении сейчас ученик. Нужно разрешение «Доступ к истории использования» (Usage access):
 * его включают один раз на очках в настройках (приложение открывает нужный экран после «Iniciar»).
 *
 * «Текущее приложение» — последнее обычное приложение, вышедшее на передний план. Меню Quest, Home
 * и системные окна не считаются: при нажатии Meta текущим остаётся игра (состояние "paused").
 * Если у приложения закрылись все окна (activity) — состояние "closed".
 */
object AppWatcher {
    @Volatile var hasAccess = false
    @Volatile var pkg: String? = null
    @Volatile var label: String? = null
    /** running — на экране; paused — открыто меню / ушло в фон, но не закрыто; closed — закрыто. */
    @Volatile var state = ""
    /** Последнее системное событие текущего приложения — для проверки на очках, как Quest сообщает о закрытии. */
    @Volatile var lastEvent = ""

    // В публичном SDK этой константы нет (она скрыта), но значение у системы — 24 (Android 10+).
    private const val ACTIVITY_DESTROYED = 24

    private var lastEventTime = 0L
    private val liveActivities = mutableSetOf<String>() // окна текущего приложения, которые ещё не закрыты
    private val ignoreCache = HashMap<String, Boolean>()

    /** Системная оболочка Quest: меню, Home, панели, экран блокировки — не «приложения». */
    private val SYSTEM_SHELL = setOf(
        "android", "com.android.systemui", "com.oculus.vrshell", "com.oculus.systemux",
        "com.oculus.shellenv", "com.oculus.guardian", "com.oculus.vrpowermanager",
        "com.oculus.os.vrlockscreen", "com.oculus.systemdriver", "com.oculus.horizon",
    )

    /** Меню / библиотека Quest с другими именами пакетов (на новых Horizon OS — «Navigator»). */
    private fun isShellByName(p: String, label: String): Boolean =
        p.contains("navigator", ignoreCase = true) || p.contains("vrshell", ignoreCase = true) ||
            label.equals("Navigator", ignoreCase = true)

    fun checkAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        hasAccess = runCatching {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return hasAccess
    }

    private fun ignored(ctx: Context, p: String): Boolean = ignoreCache.getOrPut(p) {
        if (p == ctx.packageName || p in SYSTEM_SHELL || isShellByName(p, labelOf(ctx, p))) return@getOrPut true
        val pm = ctx.packageManager
        val home = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
        }.getOrNull()
        // Без значка запуска — служебный компонент системы, а не приложение, которое открывает ученик.
        p == home || runCatching { pm.getLaunchIntentForPackage(p) == null }.getOrDefault(true)
    }

    /** Вызывается раз в секунду на потоке захвата. */
    fun poll(ctx: Context) {
        if (!checkAccess(ctx)) return
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val from = if (lastEventTime == 0L) now - 6 * 3600_000L else lastEventTime + 1
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            lastEventTime = maxOf(lastEventTime, e.timeStamp)
            val p = e.packageName ?: continue
            if (ignored(ctx, p)) continue
            val activity = e.className ?: ""
            if (p == pkg || e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastEvent = when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> "RESUMED"
                    UsageEvents.Event.ACTIVITY_PAUSED -> "PAUSED"
                    UsageEvents.Event.ACTIVITY_STOPPED -> "STOPPED"
                    ACTIVITY_DESTROYED -> "DESTROYED"
                    else -> lastEvent
                }
            }
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (p != pkg) {
                        pkg = p
                        label = labelOf(ctx, p)
                        liveActivities.clear()
                    }
                    liveActivities += activity
                    state = "running"
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (p == pkg && state != "closed") {
                        liveActivities += activity
                        state = "paused"
                    }
                ACTIVITY_DESTROYED -> if (p == pkg) {
                    liveActivities -= activity
                    if (liveActivities.isEmpty()) state = "closed"
                }
            }
        }
    }

    private fun labelOf(ctx: Context, p: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
    }.getOrDefault(p)

    fun reset() {
        lastEventTime = 0L
        liveActivities.clear()
        pkg = null; label = null; state = ""; lastEvent = ""
    }
}
