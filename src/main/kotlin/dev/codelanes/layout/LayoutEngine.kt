package dev.codelanes.layout

import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.LinkKind

data class Size(val width: Int, val height: Int)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    /** True if the rects intersect or come closer than [gap]. */
    fun overlaps(other: Rect, gap: Int = 0): Boolean =
        x < other.right + gap && other.x < right + gap && y < other.bottom + gap && other.y < bottom + gap
}

data class Point(val x: Int, val y: Int)

data class Layout(val rects: Map<String, Rect>)

/** How blocks are arranged: lanes left to right (default), one column, or a top-down tree. */
enum class LayoutMode { LANES, COLUMN, TREE }

/**
 * Left-to-right layered layout: what a type builds on sits left of it, what it owns sits right of it.
 * Pinned blocks keep their positions; everything else avoids them.
 */
object LayoutEngine {
    const val H_GAP = 96
    const val V_GAP = 24
    const val PIN_GAP = 16
    const val MAX_COLUMN_HEIGHT = 1400
    const val MAX_ROW_WIDTH = 2400
    const val TREE_V_GAP = 96

    private val LANE_KINDS = listOf(BlockKind.PARENT, BlockKind.INTERFACE, BlockKind.TRAIT, BlockKind.DEPENDENCY)
    private val STRUCTURAL = setOf(LinkKind.EXTENDS, LinkKind.IMPLEMENTS, LinkKind.USES, LinkKind.INJECTS, LinkKind.OWNS, LinkKind.IMPLEMENTED_BY, LinkKind.CALLS_INTO)

    fun layout(model: BlockModel, sizeOf: (Block) -> Size, pins: Map<String, Point> = emptyMap(), mode: LayoutMode = LayoutMode.LANES): Layout {
        val rank = ranks(model, flattenChain = mode != LayoutMode.TREE)
        val columns = orderedColumns(model, rank).map { lane -> lane.filter { it.id !in pins } }
        val classRank = model.ofKind(BlockKind.CLASS).firstOrNull()?.let { rank.getValue(it.id) } ?: 0
        val auto = when (mode) {
            LayoutMode.LANES -> place(columns, classRank, sizeOf)
            LayoutMode.COLUMN -> placeVertically(columns, classRank, sizeOf)
            LayoutMode.TREE -> placeRows(columns, sizeOf)
        }
        val pinned = model.blocks.filter { it.id in pins }.associate { block ->
            val size = sizeOf(block)
            val at = pins.getValue(block.id)
            block.id to Rect(at.x, at.y, size.width, size.height)
        }
        return Layout(applyPins(model, auto, pinned))
    }

    /** Longest-path ranks over structural links, compacted so sources sit next to what they feed. */
    private fun ranks(model: BlockModel, flattenChain: Boolean): Map<String, Int> {
        val kindOf = model.blocks.associate { it.id to it.kind }
        // A method added from search has no caller: treat it as hanging off the class like the followed chain
        // (one lane after the methods), not as a source on the far left.
        val chainSteps = setOf(LinkKind.CALLS_INTO, LinkKind.IMPLEMENTED_BY)
        val orphanEdges = model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            model.ofKind(BlockKind.CALLEE)
                .filter { callee -> model.links.none { it.to == callee.id && it.kind in chainSteps } }
                .map { dev.codelanes.model.Link(LinkKind.IMPLEMENTED_BY, cls.id, it.id) }
        }.orEmpty()
        val edges = model.links.filter { it.kind in STRUCTURAL } + orphanEdges
        // Implementers of an opened type go one lane further than its own methods (type | methods | implementers);
        // inside a followed chain an implementation is simply the next level after its interface method.
        fun weight(edge: dev.codelanes.model.Link): Int =
            if (edge.kind == LinkKind.IMPLEMENTED_BY && kindOf[edge.from] != BlockKind.CALLEE) 2 else 1
        val rank = model.blocks.associate { it.id to 0 }.toMutableMap()
        for (pass in model.blocks.indices) {
            var changed = false
            for (edge in edges) {
                val wanted = rank.getValue(edge.from) + weight(edge)
                if (rank.getValue(edge.to) < wanted) {
                    rank[edge.to] = wanted
                    changed = true
                }
            }
            if (!changed) break
        }
        // Pull sources down next to what they feed, respecting each edge's weight.
        for (pass in model.blocks.indices) {
            var changed = false
            for (block in model.blocks) {
                val latest = edges.filter { it.from == block.id }.minOfOrNull { rank.getValue(it.to) - weight(it) } ?: continue
                if (latest > rank.getValue(block.id)) {
                    rank[block.id] = latest
                    changed = true
                }
            }
            if (!changed) break
        }
        model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            model.ofKind(BlockKind.HEADER).forEach { rank[it.id] = rank.getValue(cls.id) }
        }
        // A followed chain is one lane (read top to bottom), not a new lane per call level.
        if (flattenChain) {
            val callees = model.ofKind(BlockKind.CALLEE)
            callees.minOfOrNull { rank.getValue(it.id) }?.let { lane -> callees.forEach { rank[it.id] = lane } }
        }
        return rank
    }

    /** Blocks per rank, ordered by neighbour barycenters (one sweep right, one sweep left); header before class. */
    private fun orderedColumns(model: BlockModel, rank: Map<String, Int>): List<List<Block>> {
        val maxRank = rank.values.maxOrNull() ?: return emptyList()
        val columns = (0..maxRank).map { r -> model.blocks.filter { rank.getValue(it.id) == r }.toMutableList() }
        val edges = model.links.filter { it.kind in STRUCTURAL }
        fun position(id: String): Int = columns[rank.getValue(id)].indexOfFirst { it.id == id }

        for (r in 1..maxRank) reorder(columns[r]) { b -> edges.filter { it.to == b.id }.map { position(it.from) } }
        for (r in maxRank - 1 downTo 0) reorder(columns[r]) { b -> edges.filter { it.from == b.id }.map { position(it.to) } }

        columns.forEach { column -> keepCallersAndCalleesTogether(column, model) }
        columns.forEach { column -> orderChain(column, model, columns) }
        model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            val column = columns[rank.getValue(cls.id)]
            val headers = column.filter { it.kind == BlockKind.HEADER }
            column.removeAll(headers)
            column.addAll(column.indexOf(cls), headers)
        }
        return columns
    }

    /** Methods are followed directly by the methods they call (depth first, otherwise source order). */
    private fun keepCallersAndCalleesTogether(column: MutableList<Block>, model: BlockModel) {
        if (column.none { it.kind == BlockKind.METHOD }) return
        val calls = model.links.filter { it.kind == LinkKind.CALLS }.groupBy({ it.from }, { it.to })
        val inColumn = column.map { it.id }.toSet()
        val byId = column.associateBy { it.id }
        val ordered = LinkedHashSet<String>()
        fun visit(id: String) {
            if (!ordered.add(id)) return
            calls[id].orEmpty().filter { it in inColumn }.sortedBy { callee -> column.indexOfFirst { it.id == callee } }.forEach(::visit)
        }
        column.forEach { visit(it.id) }
        val sorted = ordered.map(byId::getValue)
        column.clear()
        column.addAll(sorted)
    }

    /** The followed-calls lane in depth-first call order: each call is followed by what it calls, top to bottom. */
    private fun orderChain(column: MutableList<Block>, model: BlockModel, columns: List<List<Block>>) {
        val chain = column.filter { it.kind == BlockKind.CALLEE }
        if (chain.isEmpty()) return
        val inChain = chain.map { it.id }.toSet()
        val steps = model.links.filter {
            (it.kind == LinkKind.CALLS_INTO || it.kind == LinkKind.IMPLEMENTED_BY) && it.to in inChain
        }
        val position = columns.flatMap { it.withIndex().map { (i, b) -> b.id to i } }.toMap()
        val roots = steps.filter { it.from !in inChain }.sortedBy { position[it.from] ?: Int.MAX_VALUE }.map { it.to }
        val ordered = LinkedHashSet<String>()
        fun visit(id: String) {
            if (!ordered.add(id)) return
            steps.filter { it.from == id }.forEach { visit(it.to) }
        }
        (roots + chain.map { it.id }).forEach(::visit)
        val byId = chain.associateBy { it.id }
        val others = column.filter { it.kind != BlockKind.CALLEE }
        column.clear()
        column.addAll(others + ordered.map(byId::getValue))
    }

    private fun reorder(column: MutableList<Block>, neighbours: (Block) -> List<Int>) {
        val sorted = column.withIndex()
            .sortedWith(compareBy({ neighbours(it.value).takeIf { n -> n.isNotEmpty() }?.average() ?: it.index.toDouble() }, { it.index }))
            .map { it.value }
        column.clear()
        column.addAll(sorted)
    }

    /**
     * Left of the class: one lane per related kind (parents, interfaces, traits, dependencies), each in its own
     * height band below the previous one (a staircase). Because the bands don't share heights, every lane can hug
     * the class (all right-aligned one gap away from it) and lines to the class still never cross other blocks.
     * The class column and everything right of it are top-aligned lanes that only grow downwards.
     */
    private fun place(columns: List<List<Block>>, classRank: Int, sizeOf: (Block) -> Size): Map<String, Rect> {
        val rects = linkedMapOf<String, Rect>()
        val left = columns.take(classRank)
        val kinds = LANE_KINDS + (left.flatten().map { it.kind }.distinct() - LANE_KINDS.toSet())
        val lanes = kinds
            .map { kind -> left.map { column -> column.filter { it.kind == kind } }.flatMap { wrap(it, sizeOf) }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        fun stackWidth(stack: List<Block>) = stack.maxOf { sizeOf(it).width }
        fun laneWidth(lane: List<List<Block>>) = lane.sumOf(::stackWidth) + H_GAP * (lane.size - 1)
        val leftWidth = lanes.maxOfOrNull(::laneWidth) ?: 0

        var bandTop = 0
        for (lane in lanes) {
            var x = leftWidth - laneWidth(lane)
            var bandBottom = bandTop
            for (stack in lane) {
                val (nextX, bottom) = placeStack(stack, x, bandTop, rects, sizeOf)
                x = nextX
                bandBottom = maxOf(bandBottom, bottom)
            }
            bandTop = bandBottom + V_GAP
        }

        var x = if (lanes.isEmpty()) 0 else leftWidth + H_GAP
        for (column in columns.drop(classRank)) {
            // The followed chain never wraps: following a request means scrolling down.
            val stacks = if (column.any { it.kind == BlockKind.CALLEE }) listOf(column) else wrap(column, sizeOf)
            for (stack in stacks) x = placeStack(stack, x, 0, rects, sizeOf).first
        }
        return rects
    }

    /**
     * Experimental: everything in one column, top to bottom, in reading order: what the class builds on (lane by
     * lane, staircase order), header and class, its methods, then implementers and the followed chain.
     */
    private fun placeVertically(columns: List<List<Block>>, classRank: Int, sizeOf: (Block) -> Size): Map<String, Rect> {
        val left = columns.take(classRank)
        val kinds = LANE_KINDS + (left.flatten().map { it.kind }.distinct() - LANE_KINDS.toSet())
        val related = kinds.flatMap { kind -> left.flatMap { column -> column.filter { it.kind == kind } } }
        val rects = linkedMapOf<String, Rect>()
        placeStack(related + columns.drop(classRank).flatten(), 0, 0, rects, sizeOf)
        return rects
    }

    /**
     * Experimental tree: every level is a row, top to bottom (what the class builds on, the class, its methods, each
     * call level of a followed chain), blocks side by side, rows centred under each other, long rows wrapped.
     */
    private fun placeRows(levels: List<List<Block>>, sizeOf: (Block) -> Size): Map<String, Rect> {
        val rows = levels.filter { it.isNotEmpty() }.flatMap { level ->
            val wrapped = mutableListOf(mutableListOf<Block>())
            var width = 0
            for (block in level) {
                val w = sizeOf(block).width
                if (wrapped.last().isNotEmpty() && width + H_GAP + w > MAX_ROW_WIDTH) {
                    wrapped += mutableListOf<Block>()
                    width = 0
                }
                width += (if (wrapped.last().isEmpty()) 0 else H_GAP) + w
                wrapped.last() += block
            }
            wrapped
        }
        fun rowWidth(row: List<Block>) = row.sumOf { sizeOf(it).width } + H_GAP * (row.size - 1)
        val widest = rows.maxOfOrNull(::rowWidth) ?: 0
        val rects = linkedMapOf<String, Rect>()
        var y = 0
        for (row in rows) {
            var x = (widest - rowWidth(row)) / 2
            var height = 0
            for (block in row) {
                val size = sizeOf(block)
                rects[block.id] = Rect(x, y, size.width, size.height)
                x += size.width + H_GAP
                height = maxOf(height, size.height)
            }
            y += height + TREE_V_GAP
        }
        return rects
    }

    /** Stacks [stack] top to bottom at [x] from [top]; returns the next lane's x and this stack's bottom. */
    private fun placeStack(stack: List<Block>, x: Int, top: Int, rects: MutableMap<String, Rect>, sizeOf: (Block) -> Size): Pair<Int, Int> {
        if (stack.isEmpty()) return x to top
        var y = top
        var width = 0
        for (block in stack) {
            val size = sizeOf(block)
            rects[block.id] = Rect(x, y, size.width, size.height)
            y += size.height + V_GAP
            width = maxOf(width, size.width)
        }
        return (x + width + H_GAP) to (y - V_GAP)
    }

    private fun wrap(column: List<Block>, sizeOf: (Block) -> Size): List<List<Block>> {
        val stacks = mutableListOf(mutableListOf<Block>())
        var height = 0
        for (block in column) {
            val h = sizeOf(block).height
            if (stacks.last().isNotEmpty() && height + V_GAP + h > MAX_COLUMN_HEIGHT) {
                stacks += mutableListOf<Block>()
                height = 0
            }
            height += (if (stacks.last().isEmpty()) 0 else V_GAP) + h
            stacks.last() += block
        }
        return stacks
    }

    /** Pinned rects stay put; auto-placed rects are pushed down (staying in their lane) until nothing overlaps. */
    private fun applyPins(model: BlockModel, auto: Map<String, Rect>, pinned: Map<String, Rect>): Map<String, Rect> {
        val result = mutableMapOf<String, Rect>()
        val placed = mutableListOf<Rect>()
        // Dragged blocks keep their spot unless that would put them on top of another dragged block
        // (e.g. after one of them was expanded): then they move down to free space.
        pinned.entries.sortedWith(compareBy({ it.value.y }, { it.value.x })).forEach { (id, start) ->
            var rect = start
            while (true) {
                val hit = placed.firstOrNull { it.overlaps(rect, PIN_GAP) } ?: break
                rect = rect.copy(y = hit.bottom + PIN_GAP)
            }
            result[id] = rect
            placed += rect
        }
        auto.entries
            .sortedWith(compareBy({ it.value.x }, { it.value.y }))
            .forEach { (id, start) ->
                var rect = start
                while (true) {
                    val hit = placed.firstOrNull { it.overlaps(rect, PIN_GAP) } ?: break
                    rect = rect.copy(y = hit.bottom + PIN_GAP)
                }
                result[id] = rect
                placed += rect
            }
        return model.blocks.associate { it.id to result.getValue(it.id) }
    }
}
