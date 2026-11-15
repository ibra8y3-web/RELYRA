package org.relyra.app.core.crypto

import java.io.ByteArrayOutputStream

/** RFC 4648 Base32, no padding. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(text: String): ByteArray? {
        val normalized = text.trim().uppercase()
        // RELYRA encodings are unpadded Base32. Reject '=' and non-canonical
        // trailing bits so two different strings cannot decode to the same bytes.
        if (normalized.isEmpty() || '=' in normalized) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in normalized) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        // Valid unpadded Base32 tails leave at most four bits, all of which must be zero.
        if (bits > 4 || buffer != 0) return null
        return out.toByteArray()
    }
}
