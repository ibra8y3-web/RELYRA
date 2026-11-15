package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.documents.CsvParser
import java.io.StringReader

class CsvParserTest {
    private fun p(s: String, max: Int = 100) = CsvParser.parse(StringReader(s), maxRows = max)

    @Test fun quotedFieldsEmbeddedNewlinesAndEscapedQuotes() {
        val r = p("a,b\n\"x,1\",\"he said \"\"hi\"\"\"\n\"l1\nl2\",z\n")
        assertEquals(listOf("a", "b"), r.rows[0])
        assertEquals(listOf("x,1", "he said \"hi\""), r.rows[1])
        assertEquals(listOf("l1\nl2", "z"), r.rows[2])
    }

    @Test fun crlfBomAndUnicode() {
        val r = p("\uFEFFاسم,city\r\nعلي,القاهرة\r\n")
        assertEquals(listOf("اسم", "city"), r.rows[0])
        assertEquals(listOf("علي", "القاهرة"), r.rows[1])
        assertEquals(2, r.rows.size)
    }

    @Test fun detectsSemicolonAndTab() {
        assertEquals(';', p("a;b;c\n1;2;3").delimiter)
        assertEquals('\t', p("a\tb\n1\t2").delimiter)
    }

    @Test fun rowCapMarksTruncated() {
        val big = (1..50).joinToString("\n") { "$it,x" }
        val r = p(big, max = 10)
        assertEquals(10, r.rows.size); assertTrue(r.truncated)
    }

    @Test fun lastRowWithoutNewlineAndSearch() {
        val r = p("a,b\nfoo,bar")
        assertEquals(2, r.rows.size)
        assertEquals(listOf(1), CsvParser.search(r.rows, "FOO"))
    }
}
