package dev.codelanes.canvas

import dev.codelanes.model.SourceRange

/** Decides which blocks light up for the symbol under the caret: where it is defined and where it is used. */
object UsageHighlight {
    enum class Level { USAGE, DEFINITION }

    /** A block's text span in a file; [excluded] parts are shown by other blocks. */
    data class Span(val id: String, val filePath: String, val range: SourceRange, val excluded: List<SourceRange> = emptyList())

    /** A usage (or the definition) of the symbol at [offset] in [filePath]. */
    data class Spot(val filePath: String, val offset: Int, val definition: Boolean)

    fun assign(spots: List<Spot>, blocks: List<Span>): Map<String, Level> {
        val result = linkedMapOf<String, Level>()
        for (spot in spots) {
            val block = blocks
                .filter { it.filePath == spot.filePath && it.range.contains(spot.offset) && it.excluded.none { e -> e.contains(spot.offset) } }
                .minByOrNull { it.range.end - it.range.start } ?: continue
            val level = if (spot.definition) Level.DEFINITION else Level.USAGE
            if (result[block.id] != Level.DEFINITION) result[block.id] = level
        }
        return result
    }
}
