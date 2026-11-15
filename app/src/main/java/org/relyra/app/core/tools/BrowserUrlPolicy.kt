package org.relyra.app.core.tools

import java.net.URI
import java.net.URISyntaxException

/**
 * Accepts only navigable HTTP(S) URLs.
 * Scheme-less host names are interpreted as HTTPS. Local files, content URIs, javascript:, intent:,
 * data: and URLs containing user-info are rejected before a WebView sees them.
 */
object BrowserUrlPolicy {
    fun normalize(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.length > 2_048 || raw.any { it.isISOControl() || it.isWhitespace() }) return null

        val candidate = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.contains("://") -> return null
            else -> "https://$raw"
        }

        val uri = try {
            URI(candidate).normalize()
        } catch (_: URISyntaxException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (uri.port !in -1..65_535) return null
        if (uri.host.any { it.isWhitespace() || it.isISOControl() }) return null
        return uri.toASCIIString()
    }
}
