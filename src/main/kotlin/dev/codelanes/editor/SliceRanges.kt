package dev.codelanes.editor

import dev.codelanes.model.SourceRange

/** Computes which parts of a document a slice editor folds away. */
object SliceRanges {
    fun hidden(text: CharSequence, range: SourceRange, excluded: List<SourceRange>): List<SourceRange> {
        val inner = excluded.sortedBy { it.start }.map { wholeLines(text, range, it) }
        val all = listOf(SourceRange(0, range.start)) + inner + SourceRange(range.end, text.length)
        return merge(all.filter { it.start < it.end })
    }

    /** Widens [e] to whole lines (and the blank lines above it) when only whitespace shares those lines. */
    private fun wholeLines(text: CharSequence, bounds: SourceRange, e: SourceRange): SourceRange {
        var start = e.start
        val lineStart = lineStartOf(text, start)
        if (isBlank(text, lineStart, start)) {
            start = lineStart
            while (start > bounds.start) {
                val previousLineStart = lineStartOf(text, start - 1)
                if (previousLineStart < bounds.start || !isBlank(text, previousLineStart, start - 1)) break
                start = previousLineStart
            }
        }
        var end = e.end
        val lineEnd = lineEndOf(text, end)
        if (isBlank(text, end, lineEnd) && lineEnd < bounds.end) end = lineEnd + 1
        return SourceRange(maxOf(start, bounds.start), minOf(end, bounds.end))
    }

    private fun merge(ranges: List<SourceRange>): List<SourceRange> {
        val result = mutableListOf<SourceRange>()
        for (r in ranges.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && r.start <= last.end) result[result.size - 1] = SourceRange(last.start, maxOf(last.end, r.end))
            else result += r
        }
        return result
    }

    internal fun lineStartOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEndOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun isBlank(text: CharSequence, from: Int, to: Int): Boolean = (from until to).all { text[it].isWhitespace() }
}
