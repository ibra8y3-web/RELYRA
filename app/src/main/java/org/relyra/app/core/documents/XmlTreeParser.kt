package org.relyra.app.core.documents

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream

/**
 * Safe XML tree builder for the viewer. Uses Android's pull parser, which does not resolve external
 * entities or fetch DTDs (no XXE); we additionally refuse any DOCTYPE so entity-expansion ("billion laughs")
 * is impossible, and cap size, depth and node count so hostile files cannot exhaust memory.
 */
object XmlTreeParser {
    class Node(
        val name: String,
        val attributes: List<Pair<String, String>>,
        val children: MutableList<Node> = ArrayList(),
        var text: String = "",
    )

    sealed interface Result {
        class Ok(val root: Node, val nodeCount: Int, val truncated: Boolean) : Result
        class Rejected(val reason: String) : Result
    }

    fun parse(
        input: InputStream,
        maxDepth: Int = 64,
        maxNodes: Int = 50_000,
        maxTextChars: Int = 4_096,
        maxBytes: Long = 20L * 1024 * 1024,
    ): Result {
        val counting = object : InputStream() {
            var total = 0L
            override fun read(): Int {
                val b = input.read(); if (b >= 0 && ++total > maxBytes) throw SizeLimit(); return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = input.read(b, off, len); if (n > 0) { total += n; if (total > maxBytes) throw SizeLimit() }; return n
            }
        }
        return try {
            val parser = Xml.newPullParser()
            // Never process DTD declarations or external entities.
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(counting, null)
            val stack = ArrayList<Node>()
            var root: Node? = null
            var count = 0
            var truncated = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> return Result.Rejected("DOCTYPE declarations are not allowed (XXE protection)")
                    XmlPullParser.START_TAG -> {
                        if (stack.size >= maxDepth) return Result.Rejected("Nesting deeper than $maxDepth")
                        if (count >= maxNodes) { truncated = true; break }
                        val attrs = (0 until parser.attributeCount).map { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                        val node = Node(parser.name, attrs)
                        count++
                        stack.lastOrNull()?.children?.add(node)
                        if (root == null) root = node
                        stack.add(node)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        val cur = stack.lastOrNull()
                        val t = parser.text?.trim().orEmpty()
                        if (cur != null && t.isNotEmpty() && cur.text.length < maxTextChars) {
                            cur.text = (cur.text + (if (cur.text.isEmpty()) "" else " ") + t).take(maxTextChars)
                        }
                    }
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                event = parser.next()
            }
            val r = root ?: return Result.Rejected("Empty document")
            Result.Ok(r, count, truncated)
        } catch (e: SizeLimit) {
            Result.Rejected("File larger than ${maxBytes / (1024 * 1024)} MB")
        } catch (e: XmlPullParserException) {
            Result.Rejected("Invalid XML: ${e.message?.take(160)}")
        }
    }

    private class SizeLimit : RuntimeException()

    /** Flattens the tree into indented lines for a lazy list, honouring collapsed paths and a search filter. */
    fun flatten(root: Node, collapsed: Set<String> = emptySet(), query: String = ""): List<Line> {
        val out = ArrayList<Line>()
        fun walk(n: Node, depth: Int, path: String) {
            val attrs = n.attributes.joinToString(" ") { "${it.first}=\"${it.second}\"" }
            val label = "<${n.name}${if (attrs.isEmpty()) "" else " $attrs"}>" + if (n.text.isEmpty()) "" else " ${n.text}"
            val match = query.isNotBlank() && label.contains(query, ignoreCase = true)
            out.add(Line(depth, label, path, n.children.isNotEmpty(), match))
            if (path !in collapsed) n.children.forEachIndexed { i, c -> walk(c, depth + 1, "$path/$i") }
        }
        walk(root, 0, "0")
        return out
    }

    class Line(val depth: Int, val label: String, val path: String, val hasChildren: Boolean, val matches: Boolean)
}
