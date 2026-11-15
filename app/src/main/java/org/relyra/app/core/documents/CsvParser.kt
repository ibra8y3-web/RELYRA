package org.relyra.app.core.documents

import java.io.Reader

/**
 * Streaming RFC 4180 CSV reader (plain Kotlin, JVM-testable). Handles quoted fields, escaped quotes,
 * CR/LF/CRLF, embedded newlines, a UTF-8 BOM and auto-detects the delimiter. Memory is bounded: callers
 * receive at most [maxRows] rows and each cell is truncated to [maxCellChars].
 */
object CsvParser {
    class Result(val delimiter: Char, val rows: List<List<String>>, val truncated: Boolean)

    fun detectDelimiter(sample: String): Char {
        val candidates = charArrayOf(',', ';', '\t', '|')
        var best = ','
        var bestCount = -1
        val firstLine = sample.removePrefix("\uFEFF").lineSequence().firstOrNull().orEmpty()
        for (c in candidates) {
            var inQuotes = false
            var n = 0
            for (ch in firstLine) {
                if (ch == '"') inQuotes = !inQuotes else if (ch == c && !inQuotes) n++
            }
            if (n > bestCount) { bestCount = n; best = c }
        }
        return best
    }

    fun parse(
        reader: Reader,
        delimiter: Char? = null,
        maxRows: Int = 50_000,
        maxCellChars: Int = 4_096,
    ): Result {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var cellWasQuoted = false
        var delim = delimiter
        var truncated = false
        var first = true
        var pendingQuote = false // saw a quote while inside quotes; next char decides escape vs close
        val sample = StringBuilder()

        fun endCell() {
            row.add(cell.toString()); cell.setLength(0); cellWasQuoted = false
        }
        fun endRow(): Boolean {
            endCell()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(ArrayList(row))
            row.clear()
            if (rows.size >= maxRows) { truncated = true; return false }
            return true
        }

        val buf = CharArray(8192)
        var prevCr = false
        loop@ while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                var ch = buf[i]
                if (first) {
                    first = false
                    if (ch == '\uFEFF') continue
                }
                if (delim == null) {
                    sample.append(ch)
                    if (ch == '\n' || sample.length > 4096) delim = detectDelimiter(sample.toString())
                }
                if (prevCr) { prevCr = false; if (ch == '\n') continue }
                if (pendingQuote) {
                    pendingQuote = false
                    if (ch == '"') { if (cell.length < maxCellChars) cell.append('"'); continue }
                    inQuotes = false
                }
                val d = delim ?: ','
                if (inQuotes) {
                    if (ch == '"') pendingQuote = true
                    else if (cell.length < maxCellChars) cell.append(ch)
                    continue
                }
                when {
                    ch == '"' && cell.isEmpty() && !cellWasQuoted -> { inQuotes = true; cellWasQuoted = true }
                    ch == d -> endCell()
                    ch == '\r' -> { prevCr = true; if (!endRow()) break@loop }
                    ch == '\n' -> if (!endRow()) break@loop
                    else -> if (cell.length < maxCellChars) cell.append(ch)
                }
            }
        }
        if (!truncated && (cell.isNotEmpty() || row.isNotEmpty() || cellWasQuoted)) endRow()
        return Result(delim ?: detectDelimiter(sample.toString()), rows, truncated)
    }

    /** Case-insensitive substring search; returns indices of matching rows. */
    fun search(rows: List<List<String>>, query: String, limit: Int = 1000): List<Int> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<Int>()
        for ((i, r) in rows.withIndex()) {
            if (r.any { it.contains(query, ignoreCase = true) }) { out.add(i); if (out.size >= limit) break }
        }
        return out
    }
}
