package org.relyra.app.core.tools

import android.content.Context
import org.relyra.app.core.crypto.KeystoreVault
import java.util.Base64

/** Keystore-wrapped storage for the per-site browser rules. */
class BrowserSiteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relyra_browser_sites", Context.MODE_PRIVATE)

    @Volatile
    private var cache: Map<String, SiteRules>? = null

    private fun load(): Map<String, SiteRules> {
        cache?.let { return it }
        val loaded: Map<String, SiteRules> = runCatching {
            val encoded = prefs.getString(KEY, null)
            if (encoded == null) {
                emptyMap()
            } else {
                val plain = KeystoreVault.unwrap(Base64.getDecoder().decode(encoded), KEY.toByteArray())
                SiteRulesCodec.decode(String(plain, Charsets.UTF_8))
            }
        }.getOrDefault(emptyMap())
        cache = loaded
        return loaded
    }

    private fun save(rules: Map<String, SiteRules>) {
        val blob = Base64.getEncoder().encodeToString(
            KeystoreVault.wrap(SiteRulesCodec.encode(rules).toByteArray(Charsets.UTF_8), KEY.toByteArray()),
        )
        prefs.edit().putString(KEY, blob).apply()
        cache = rules
    }

    fun rulesFor(url: String): SiteRules {
        val key = SiteRulesCodec.siteKey(url) ?: return SiteRules()
        return load()[key] ?: SiteRules()
    }

    fun update(url: String, rules: SiteRules) {
        val key = SiteRulesCodec.siteKey(url) ?: return
        val next = LinkedHashMap(load())
        if (rules.isDefault) next.remove(key) else next[key] = rules
        save(next)
    }

    fun reset(url: String) = update(url, SiteRules())

    fun resetAll() {
        prefs.edit().remove(KEY).apply()
        cache = emptyMap()
    }

    private companion object {
        const val KEY = "site_rules"
    }
}
