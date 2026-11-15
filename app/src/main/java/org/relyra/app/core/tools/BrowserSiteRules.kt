package org.relyra.app.core.tools

import org.json.JSONObject
import java.net.URI

enum class SiteDecision { ALLOW, BLOCK }

/** Per-site choices. A null value means "use the browser default" (or "ask" for camera and microphone). */
data class SiteRules(
    val javaScript: SiteDecision? = null,
    val cookies: SiteDecision? = null,
    val camera: SiteDecision? = null,
    val microphone: SiteDecision? = null,
) {
    val isDefault: Boolean
        get() = javaScript == null && cookies == null && camera == null && microphone == null
}

/** Pure helpers for site keys and for the stored form of the rules. */
object SiteRulesCodec {
    const val MAX_SITES = 500

    /** Lower-case host name of a URL, or null when the URL has no host. */
    fun siteKey(url: String): String? {
        val host = try {
            URI(url).host
        } catch (_: Exception) {
            null
        }
        return host?.lowercase()?.takeIf { it.isNotBlank() }
    }

    fun encode(rules: Map<String, SiteRules>): String {
        val root = JSONObject()
        var count = 0
        for ((host, value) in rules) {
            if (value.isDefault) continue
            if (count >= MAX_SITES) break
            val item = JSONObject()
            value.javaScript?.let { item.put("js", it.name) }
            value.cookies?.let { item.put("ck", it.name) }
            value.camera?.let { item.put("cam", it.name) }
            value.microphone?.let { item.put("mic", it.name) }
            root.put(host, item)
            count++
        }
        return root.toString()
    }

    fun decode(json: String): Map<String, SiteRules> {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, SiteRules>()
        val keys = root.keys()
        while (keys.hasNext() && result.size < MAX_SITES) {
            val host = keys.next()
            val item = root.optJSONObject(host) ?: continue
            val rules = SiteRules(
                javaScript = decision(item.optString("js")),
                cookies = decision(item.optString("ck")),
                camera = decision(item.optString("cam")),
                microphone = decision(item.optString("mic")),
            )
            if (!rules.isDefault) result[host] = rules
        }
        return result
    }

    private fun decision(name: String): SiteDecision? = SiteDecision.values().firstOrNull { it.name == name }
}
