package org.relyra.app.core.backup

import android.content.Context
import androidx.core.content.edit

/** Where the user's WebDAV/NAS backups go. The password is NOT here: it lives in SecureStore (Android Keystore wrapped). */
data class WebDavConfig(
    val url: String = "",
    val user: String = "",
    val folder: String = "relyra",
    val allowHttp: Boolean = false,
) {
    val configured: Boolean get() = url.isNotBlank()
}

class WebDavSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_webdav", Context.MODE_PRIVATE)

    fun read() = WebDavConfig(
        prefs.getString("url", "") ?: "", prefs.getString("user", "") ?: "",
        prefs.getString("folder", "relyra") ?: "relyra", prefs.getBoolean("allow_http", false),
    )

    fun write(c: WebDavConfig) = prefs.edit {
        putString("url", c.url.trim()); putString("user", c.user.trim())
        putString("folder", c.folder.trim().trim('/').ifEmpty { "relyra" }); putBoolean("allow_http", c.allowHttp)
    }

    companion object { const val KEEP_BACKUPS = 5 }
}
