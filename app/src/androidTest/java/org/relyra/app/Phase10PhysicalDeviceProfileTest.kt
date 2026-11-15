package org.relyra.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device profile capture for Phase 10.
 * Emulators skip this test. Only non-secret device and capability metadata is recorded.
 */
@RunWith(AndroidJUnit4::class)
class Phase10PhysicalDeviceProfileTest {
    @Test
    fun capturesPhysicalDeviceProfileAndAndroidRuntimeCapabilities() {
        assumeFalse("Phase 10 profile requires a physical device", isEmulator())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName == "org.relyra.app")
        assertTrue(Build.VERSION.SDK_INT >= 29)
        assertTrue(Build.FINGERPRINT.isNotBlank())

        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val batteryManager = checkNotNull(context.getSystemService(BatteryManager::class.java))
        val powerManager = checkNotNull(context.getSystemService(PowerManager::class.java))

        val transports = JSONArray()
        val active = connectivity.activeNetwork
        val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.put("bluetooth")
        }

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val features = JSONArray().apply {
            listOf(
                "android.hardware.camera",
                "android.hardware.camera.front",
                "android.hardware.bluetooth",
                "android.hardware.bluetooth_le",
                "android.hardware.wifi.direct",
                "android.hardware.telephony",
                "android.hardware.microphone"
            ).filter { context.packageManager.hasSystemFeature(it) }.forEach { put(it) }
        }

        val profile = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("networkTransports", transports)
            .put("batteryPercent", percent)
            .put("powerSaveMode", powerManager.isPowerSaveMode)
            .put("hardwareFeatures", features)
            .put("batteryCapacityPercent", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))

        Log.i("RELYRA_PHASE10_PROFILE", profile.toString())
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)
}
