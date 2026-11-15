package org.relyra.app.core.transfer

import org.relyra.app.core.messaging.AttachmentLimits
import java.net.URI
import java.net.URISyntaxException

/**
 * File-server addresses. HTTPS only. Addresses the user types may point at their own LAN server; addresses that
 * arrive inside someone else's message may not point at private or local networks (nobody should be able to make
 * your phone knock on your router).
 */
object BlossomUrl {
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".localdomain", ".home.arpa")

    fun normalize(input: String, allowPrivate: Boolean): String? {
        var s = input.trim()
        if (s.isEmpty() || s.length > AttachmentLimits.MAX_URL || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = try { URI(s) } catch (e: URISyntaxException) { return null }
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.contains('.') || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (!allowPrivate && isPrivateHost(host)) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }

    fun isPrivateHost(host: String): Boolean {
        val m = IPV4.matchEntire(host)
        if (m != null) {
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return true
            return o[0] == 0 || o[0] == 10 || o[0] == 127 ||
                (o[0] == 169 && o[1] == 254) ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 100 && o[1] in 64..127) ||
                o[0] >= 224
        }
        return LOCAL_SUFFIXES.any { host.endsWith(it) }
    }

    fun hostOf(url: String): String = try { URI(url).host ?: url } catch (e: URISyntaxException) { url }
}
