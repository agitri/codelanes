package com.readcodelikeahuman.editor

import com.readcodelikeahuman.model.SourceRange

/** Where the caret of a slice editor is allowed to rest. */
object CaretPolicy {
    fun adjust(text: CharSequence, bounds: SourceRange, folds: List<SourceRange>, offset: Int, previous: Int): Int {
        var o = offset.coerceIn(bounds.start, bounds.end)
        val forward = o >= previous
        folds.firstOrNull { o > it.start && o < it.end }?.let { o = if (forward) it.end else it.start }
        val isHole = o > bounds.start && o < bounds.end && SliceRanges.lineStartOf(text, o) == o &&
            folds.any { it.start == o }
        if (!isHole) return o
        val hole = folds.first { it.start == o }
        return if (!forward && o - 1 >= bounds.start) o - 1 else hole.end
    }
}
