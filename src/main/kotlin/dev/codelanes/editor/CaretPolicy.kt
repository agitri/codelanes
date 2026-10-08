package dev.codelanes.editor

import dev.codelanes.model.SourceRange

/** Where the caret of a slice editor is allowed to rest, and what it may delete. */
object CaretPolicy {
    private const val MAX_STEPS = 10_000

    fun adjust(text: CharSequence, bounds: SourceRange, folds: List<SourceRange>, offset: Int, previous: Int): Int {
        val forward = offset >= previous
        var o = offset.coerceIn(bounds.start, bounds.end)
        repeat(MAX_STEPS) {
            val next = step(text, bounds, folds, o, forward)
            if (next == o) return o
            o = next
        }
        return o
    }

    /** True if deleting `[from, to)` only touches text this slice shows and doesn't glue a hidden line onto it. */
    fun canDelete(text: CharSequence, bounds: SourceRange, folds: List<SourceRange>, from: Int, to: Int): Boolean {
        if (from >= to || from < bounds.start || to > bounds.end) return false
        if (folds.any { it.start < to && it.end > from }) return false
        return folds.none { it.start == to && to > bounds.start && SliceRanges.lineStartOf(text, to) == to }
    }

    private fun step(text: CharSequence, bounds: SourceRange, folds: List<SourceRange>, o: Int, forward: Boolean): Int {
        folds.firstOrNull { o > it.start && o < it.end }?.let { return if (forward) it.end else it.start }

        val hole = folds.firstOrNull { it.start == o && o > bounds.start && o < bounds.end && SliceRanges.lineStartOf(text, o) == o }
        if (hole != null) return if (!forward && o - 1 >= bounds.start) o - 1 else hole.end

        // A line that also holds hidden text belongs to another block: skip the whole line.
        val lineStart = SliceRanges.lineStartOf(text, o)
        val lineEnd = lineEndOf(text, o)
        val sharesLineWithHidden = folds.any { f ->
            maxOf(f.start, lineStart, bounds.start) < minOf(f.end, lineEnd, bounds.end)
        }
        if (sharesLineWithHidden) {
            val ahead = lineEnd + 1
            val behind = lineStart - 1
            return when {
                forward && ahead <= bounds.end -> ahead
                behind >= bounds.start -> behind
                ahead <= bounds.end -> ahead
                else -> o
            }
        }

        // The slice ends exactly where the next block's line starts (e.g. the header): stay on our last line.
        if (o == bounds.end && o > bounds.start && o < text.length && lineStart == o) return o - 1
        return o
    }

    private fun lineEndOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }
}
