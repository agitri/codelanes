package dev.codelanes.model

enum class BlockKind { HEADER, INTERFACE, PARENT, TRAIT, DEPENDENCY, CLASS, METHOD, IMPLEMENTER, IMPLEMENTATION, MORE, CALLEE }

enum class LinkKind { IMPLEMENTS, EXTENDS, USES, INJECTS, OWNS, CALLS, OVERRIDES, IMPLEMENTED_BY, CALLS_INTO }

/** Half-open character range `[start, end)` in a file. */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start >= 0 && start <= end) { "Invalid range [$start, $end)" }
    }

    fun contains(offset: Int): Boolean = offset >= start && offset < end
}

/**
 * One block on the canvas: a slice of [filePath] covering [range], minus [excluded]
 * sub-ranges that are shown by other blocks (e.g. methods inside the class block).
 */
data class Block(
    val id: String,
    val kind: BlockKind,
    val title: String,
    val filePath: String,
    val range: SourceRange,
    val excluded: List<SourceRange> = emptyList(),
    val collapsed: Boolean = false,
    /** One line per member signature; shown in the body of a collapsed block. */
    val summary: List<String> = emptyList(),
) {
    init {
        require(excluded.all { it.start >= range.start && it.end <= range.end }) {
            "Excluded ranges of $id must lie inside $range"
        }
    }
}

/** [targetRange]: where in the target block's file this link lands (e.g. the overridden method), if it lands on one spot. */
data class Link(val kind: LinkKind, val from: String, val to: String, val targetRange: SourceRange? = null)

data class BlockModel(val blocks: List<Block>, val links: List<Link>) {
    private val byId: Map<String, Block> = blocks.associateBy { it.id }

    init {
        require(byId.size == blocks.size) { "Block ids must be unique" }
        links.forEach { require(it.from in byId && it.to in byId) { "Link $it points to a missing block" } }
    }

    fun block(id: String): Block = byId.getValue(id)

    fun ofKind(kind: BlockKind): List<Block> = blocks.filter { it.kind == kind }
}

sealed interface BuildResult {
    data class Supported(val model: BlockModel) : BuildResult
    data class Unsupported(val reason: String) : BuildResult
}
