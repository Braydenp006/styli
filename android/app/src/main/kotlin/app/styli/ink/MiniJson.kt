package app.styli.ink

/**
 * Just enough JSON for the small files beside the notes (`file.json`, `.folder.json`, `trash.json`): one object of
 * strings, whole numbers, true or false, null, and lists of strings. Written as readable, indented text. There is
 * no JSON library on the JVM test classpath, and these files are too small to need one.
 */
object MiniJson {
    fun write(map: Map<String, Any?>): String = map.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  ${quote(k)}: ${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long -> v.toString()
        is String -> quote(v)
        is List<*> -> v.joinToString(", ", "[", "]") { quote(it as String) }
        else -> throw IllegalArgumentException("cannot write ${v::class.simpleName}")
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\t' -> append("\\t"); c == '\r' -> append("\\r")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** The object in [text]; throws IllegalArgumentException if it is not one this writer could have made. */
    fun parse(text: String): Map<String, Any?> {
        var i = 0
        fun fail(): Nothing = throw IllegalArgumentException("bad JSON near position $i")
        fun ws() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); if (i >= text.length || text[i] != c) fail(); i++ }
        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= text.length) fail()
                val c = text[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= text.length) fail()
                when (val e = text[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> { if (i + 4 > text.length) fail(); sb.append(text.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    '"', '\\', '/' -> sb.append(e)
                    else -> fail()
                }
            }
        }
        fun value(): Any? {
            ws()
            if (i >= text.length) fail()
            return when {
                text[i] == '"' -> str()
                text[i] == '[' -> {
                    i++; val out = ArrayList<String>(); ws()
                    if (text[i] == ']') { i++; return out }
                    while (true) { out.add(str()); ws(); if (text[i] == ',') i++ else { expect(']'); break } }
                    out
                }
                text.startsWith("true", i) -> { i += 4; true }
                text.startsWith("false", i) -> { i += 5; false }
                text.startsWith("null", i) -> { i += 4; null }
                else -> {
                    val start = i
                    while (i < text.length && (text[i] == '-' || text[i].isDigit())) i++
                    text.substring(start, i).toLongOrNull() ?: fail()
                }
            }
        }
        val out = LinkedHashMap<String, Any?>()
        expect('{'); ws()
        if (text[i] == '}') return out
        while (true) {
            val k = str(); expect(':'); out[k] = value(); ws()
            if (text[i] == ',') i++ else { expect('}'); break }
        }
        return out
    }
}
