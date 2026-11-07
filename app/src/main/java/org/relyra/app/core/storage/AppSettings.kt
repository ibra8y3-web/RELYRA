package org.relyra.app.core.storage

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** Preferences that are not secrets. Shared by the UI, the notifier and the background service. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_settings", Context.MODE_PRIVATE)

    private val _notifications = MutableStateFlow(prefs.getBoolean(K_NOTIF, true))
    private val _notifPreview = MutableStateFlow(prefs.getBoolean(K_NOTIF_PREVIEW, false))
    private val _listPreview = MutableStateFlow(prefs.getBoolean(K_LIST_PREVIEW, true))
    private val _keepConnected = MutableStateFlow(prefs.getBoolean(K_KEEP, false))
    private val _blockShots = MutableStateFlow(prefs.getBoolean(K_SHOTS, false))
    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(K_WIFI, false))
    private val _theme = MutableStateFlow(readTheme())

    /** App-level switch. Android's own notification permission is separate and can still block them. */
    val notifications: StateFlow<Boolean> = _notifications
    /** Show the message text inside notifications. Off by default: a notification only says who wrote. */
    val notifPreview: StateFlow<Boolean> = _notifPreview
    /** Show the last message under each conversation in the list. */
    val listPreview: StateFlow<Boolean> = _listPreview
    /** Keep a visible foreground service running so messages arrive while the app is closed. */
    val keepConnected: StateFlow<Boolean> = _keepConnected
    val blockScreenshots: StateFlow<Boolean> = _blockShots
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnly
    val theme: StateFlow<ThemeMode> = _theme

    private fun readTheme(): ThemeMode =
        ThemeMode.entries.getOrNull(prefs.getInt(K_THEME, 0)) ?: ThemeMode.SYSTEM

    fun setNotifications(v: Boolean) { prefs.edit { putBoolean(K_NOTIF, v) }; _notifications.value = v }
    fun setNotifPreview(v: Boolean) { prefs.edit { putBoolean(K_NOTIF_PREVIEW, v) }; _notifPreview.value = v }
    fun setListPreview(v: Boolean) { prefs.edit { putBoolean(K_LIST_PREVIEW, v) }; _listPreview.value = v }
    fun setKeepConnected(v: Boolean) { prefs.edit { putBoolean(K_KEEP, v) }; _keepConnected.value = v }
    fun setBlockScreenshots(v: Boolean) { prefs.edit { putBoolean(K_SHOTS, v) }; _blockShots.value = v }
    fun setWifiOnlyDownloads(v: Boolean) { prefs.edit { putBoolean(K_WIFI, v) }; _wifiOnly.value = v }
    fun setTheme(m: ThemeMode) { prefs.edit { putInt(K_THEME, m.ordinal) }; _theme.value = m }

    private companion object {
        const val K_NOTIF = "notifications_on"
        const val K_NOTIF_PREVIEW = "notif_preview"
        const val K_LIST_PREVIEW = "list_preview"
        const val K_KEEP = "keep_connected"
        const val K_SHOTS = "block_screenshots"
        const val K_WIFI = "wifi_only_downloads"
        const val K_THEME = "theme_mode"
    }
}
