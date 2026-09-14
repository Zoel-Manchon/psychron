package dev.psychron.node

/**
 * Indents a contract message and labels every token, for display.
 *
 * Pure and deliberately narrow: it formats the JSON this app produces — no
 * whitespace, no escapes beyond quotes — rather than being a general parser. The
 * screen only ever shows one kind of message, and a general formatter would be the
 * largest and least tested piece of code in the app.
 */
object JsonPretty {
    enum class Kind { KEY, STRING, NUMBER, LITERAL, PUNCTUATION, SPACE }

    data class Segment(val text: String, val kind: Kind)

    fun segments(json: String, indent: String = "  "): List<Segment> {
        val out = mutableListOf<Segment>()
        var depth = 0
        var i = 0

        fun newline() = out.add(Segment("\n" + indent.repeat(depth), Kind.SPACE))

        while (i < json.length) {
            val c = json[i]
            when {
                c == '{' -> {
                    out.add(Segment("{", Kind.PUNCTUATION))
                    // An object with nothing in it stays on one line.
                    if (json.getOrNull(i + 1) == '}') {
                        out.add(Segment("}", Kind.PUNCTUATION)); i += 2; continue
                    }
                    depth++; newline()
                }
                c == '}' -> { depth--; newline(); out.add(Segment("}", Kind.PUNCTUATION)) }
                c == ',' -> { out.add(Segment(",", Kind.PUNCTUATION)); newline() }
                c == ':' -> out.add(Segment(": ", Kind.PUNCTUATION))
                c == '"' -> {
                    var j = i + 1
                    while (j < json.length && json[j] != '"') { if (json[j] == '\\') j++; j++ }
                    val token = json.substring(i, (j + 1).coerceAtMost(json.length))
                    // A string followed by a colon is a key; anything else is a value.
                    val kind = if (json.getOrNull(j + 1) == ':') Kind.KEY else Kind.STRING
                    out.add(Segment(token, kind))
                    i = j + 1; continue
                }
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < json.length && (json[j].isDigit() || json[j] in ".eE+-")) j++
                    out.add(Segment(json.substring(i, j), Kind.NUMBER))
                    i = j; continue
                }
                json.startsWith("null", i) -> { out.add(Segment("null", Kind.LITERAL)); i += 4; continue }
                json.startsWith("true", i) -> { out.add(Segment("true", Kind.LITERAL)); i += 4; continue }
                json.startsWith("false", i) -> { out.add(Segment("false", Kind.LITERAL)); i += 5; continue }
                c.isWhitespace() -> Unit
                else -> out.add(Segment(c.toString(), Kind.PUNCTUATION))
            }
            i++
        }
        return out
    }

    fun text(json: String): String = segments(json).joinToString("") { it.text }
}
