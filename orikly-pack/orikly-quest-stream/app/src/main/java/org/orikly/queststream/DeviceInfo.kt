package org.orikly.queststream

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address

object DeviceInfo {

    /**
     * Имя устройства. Сначала системное имя (его, предположительно, задаёт HMS),
     * потом имя Bluetooth, иначе модель + хвост ANDROID_ID.
     */
    fun name(ctx: Context): String {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching { Settings.Secure.getString(cr, "bluetooth_name") }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val id = Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID) ?: ""
        return "${Build.MODEL}-${id.takeLast(4)}"
    }

    /** Уникальный номер устройства: имена у очков могут совпадать (на Quest это часто просто «Quest 3»). */
    fun id(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    fun ip(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    @Suppress("DEPRECATION")
    fun rssi(ctx: Context): Int? {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return wm.connectionInfo?.rssi?.takeIf { it > -127 }
    }

    fun battery(ctx: Context): Int? {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return null
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }
}
