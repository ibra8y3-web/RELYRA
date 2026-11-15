package org.relyra.app.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

enum class NetKind { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

class NetInfo(val kind: NetKind, val metered: Boolean, val vpn: Boolean)
class BatteryInfo(val percent: Int, val charging: Boolean, val powerSaver: Boolean)
class MemoryStats(val appUsedBytes: Long, val appMaxBytes: Long, val deviceFreeBytes: Long, val deviceTotalBytes: Long, val low: Boolean)

/** Real readings from Android. Nothing is estimated or made up; a value Android cannot give is reported as unknown. */
object DeviceInfo {
    fun network(ctx: Context): NetInfo {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetInfo(NetKind.NONE, true, false)
        val net = cm.activeNetwork ?: return NetInfo(NetKind.NONE, true, false)
        val caps = cm.getNetworkCapabilities(net) ?: return NetInfo(NetKind.NONE, true, false)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
            else -> NetKind.OTHER
        }
        return NetInfo(
            kind,
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    /** True when the current connection is Wi-Fi or Ethernet (not mobile data, and not unknown). */
    fun onUnlimitedLink(ctx: Context): Boolean {
        val n = network(ctx)
        return n.kind == NetKind.WIFI || n.kind == NetKind.ETHERNET
    }

    fun battery(ctx: Context): BatteryInfo {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pm = ctx.getSystemService(PowerManager::class.java)
        return BatteryInfo(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            powerSaver = pm?.isPowerSaveMode == true,
        )
    }

    fun memory(ctx: Context): MemoryStats {
        val rt = Runtime.getRuntime()
        val info = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return MemoryStats(rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), info.availMem, info.totalMem, info.lowMemory)
    }

    /** Android may stop background work of apps that are "optimized". Unoptimized apps are left running more reliably. */
    fun batteryOptimized(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
