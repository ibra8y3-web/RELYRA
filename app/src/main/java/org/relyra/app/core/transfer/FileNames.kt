package org.relyra.app.core.transfer

/** Names and types arrive from other people, so they are cleaned before they touch the file system or the UI. */
object FileNames {
    private const val BAD = "/\\:*?\"<>|"

    fun sanitize(raw: String): String {
        val cleaned = raw
            .map { c -> if (c.isISOControl() || BAD.indexOf(c) >= 0) '_' else c }
            .joinToString("")
            .trim()
            .trim('.')
            .trim()
        var limited = if (cleaned.length > 120) cleaned.take(120) else cleaned
        if (limited.isNotEmpty() && limited.last().isHighSurrogate()) limited = limited.dropLast(1)
        return if (limited.isBlank()) "file" else limited
    }

    fun sanitizeMime(raw: String): String {
        val m = raw.filter { it.isLetterOrDigit() || it == '/' || it == '.' || it == '+' || it == '-' || it == '_' }.take(100)
        val ok = m.count { it == '/' } == 1 && !m.startsWith("/") && !m.endsWith("/")
        return if (ok) m else "application/octet-stream"
    }
}
