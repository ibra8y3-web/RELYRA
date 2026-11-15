package org.relyra.app.core.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.crypto.KeystoreVault
import java.util.Base64

data class BrowserEntry(val url: String, val title: String, val time: Long)

/**
 * Bookmarks and optional browsing history for the built-in browser.
 * Both lists are wrapped with the Android Keystore key before they are written, so they are never
 * stored as plain text. History is off by default and is deleted when the user turns it off.
 */
class BrowserStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_browser", Context.MODE_PRIVATE)

    private fun readArray(name: String): JSONArray = runCatching {
        val encoded = prefs.getString(name, null) ?: return@runCatching JSONArray()
        val plain = KeystoreVault.unwrap(Base64.getDecoder().decode(encoded), name.toByteArray())
        JSONArray(String(plain, Charsets.UTF_8))
    }.getOrDefault(JSONArray())

    private fun entries(name: String): List<BrowserEntry> {
        val array = readArray(name)
        val result = ArrayList<BrowserEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("u")
            if (url.isBlank()) continue
            result.add(BrowserEntry(url, item.optString("t"), item.optLong("w")))
        }
        return result
    }

    private fun save(name: String, list: List<BrowserEntry>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("u", it.url).put("t", it.title).put("w", it.time)) }
        val blob = Base64.getEncoder().encodeToString(
            KeystoreVault.wrap(array.toString().toByteArray(Charsets.UTF_8), name.toByteArray()),
        )
        prefs.edit().putString(name, blob).apply()
    }

    fun bookmarks(): List<BrowserEntry> = entries(BOOKMARKS)

    fun history(): List<BrowserEntry> = entries(HISTORY)

    /** Adds the page when it is not bookmarked and removes it when it is. Returns true when it is now bookmarked. */
    fun toggleBookmark(url: String, title: String): Boolean {
        val current = bookmarks()
        val exists = current.any { it.url == url }
        val next = if (exists) {
            current.filterNot { it.url == url }
        } else {
            (listOf(BrowserEntry(url, title.take(200), System.currentTimeMillis())) + current).take(MAX_BOOKMARKS)
        }
        save(BOOKMARKS, next)
        return !exists
    }

    fun addHistory(url: String, title: String) {
        if (!historyEnabled || url.isBlank()) return
        val next = (listOf(BrowserEntry(url, title.take(200), System.currentTimeMillis())) +
            history().filterNot { it.url == url }).take(MAX_HISTORY)
        save(HISTORY, next)
    }

    fun clearHistory() {
        prefs.edit().remove(HISTORY).apply()
    }

    var historyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HISTORY_ON, value).apply()
            if (!value) clearHistory()
        }

    private companion object {
        const val BOOKMARKS = "bookmarks"
        const val HISTORY = "history"
        const val KEY_HISTORY_ON = "history_enabled"
        const val MAX_BOOKMARKS = 200
        const val MAX_HISTORY = 100
    }
}
