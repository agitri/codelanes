package dev.codelanes.python

/** Plain-text reading of Python source for edits made between rebuilds (no PSI: the session hands over text). */
internal object PythonSource {
    private val MAIN_GUARD = Regex("""^if\s+(__name__\s*==\s*(['"])__main__\2|(['"])__main__\3\s*==\s*__name__)\s*:""")

    fun lineStart(text: CharSequence, offset: Int): Int {
        var i = minOf(offset, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** The spaces and tabs that start the line holding [offset]. */
    fun indentOfLine(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(start, i).toString()
    }

    /** [end] moved back over trailing whitespace, not before [start]. */
    fun trimmedEnd(text: CharSequence, start: Int, end: Int): Int {
        var i = end
        while (i > start && text[i - 1].isWhitespace()) i--
        return i
    }

    /** Line starts of [from, to), each with its text. */
    private fun lines(text: CharSequence, from: Int, to: Int): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var i = lineStart(text, from)
        while (i < to) {
            var j = i
            while (j < to && text[j] != '\n') j++
            result += i to text.subSequence(i, j).toString()
            i = j + 1
        }
        return result
    }

    /** Where the last top-level statement in [from, to) starts, if it is `if __name__ == "__main__":`. */
    fun mainGuardStart(text: CharSequence, from: Int, to: Int): Int? {
        val last = lines(text, from, to).lastOrNull { (_, line) ->
            line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")
        } ?: return null
        return last.first.takeIf { MAIN_GUARD.containsMatchIn(last.second) }
    }

    /** Just after the `:` that ends the `class` header starting in [from, to) (decorators skipped), or null. */
    fun headerEnd(text: CharSequence, from: Int, to: Int): Int? {
        val classLine = lines(text, from, to).firstOrNull { (_, line) -> line.trimStart().let { it.startsWith("class ") || it.startsWith("class\t") } }
            ?: return null
        var depth = 0
        var quote: Char? = null
        var i = classLine.first
        while (i < to) {
            val c = text[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '#' -> while (i < to && text[i] != '\n') i++
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ':' && depth == 0 -> return i + 1
            }
            i++
        }
        return null
    }

    /** The statement on the header line after a header ending at [headerEnd] (`pass` in `class E: pass`), up to [end]; null if none. */
    fun oneLineBody(text: CharSequence, headerEnd: Int, end: Int): String? {
        if (headerEnd >= end) return null
        val rest = text.subSequence(headerEnd, end).toString()
        if ('\n' in rest) return null
        return rest.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
    }

    /** The indent of the first statement after a header ending at [headerEnd]; null for a one-line body or none. */
    fun bodyIndent(text: CharSequence, headerEnd: Int, to: Int): String? {
        var i = headerEnd
        while (i < to && text[i] != '\n') {
            if (text[i] == '#') break
            if (!text[i].isWhitespace()) return null
            i++
        }
        return lines(text, minOf(i + 1, to), to)
            .firstOrNull { (start, line) -> start > headerEnd && line.isNotBlank() && !line.trimStart().startsWith("#") }
            ?.let { (start, _) -> indentOfLine(text, start) }
    }
}
