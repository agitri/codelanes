package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.LinkKind

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

/**
 * Left-to-right layered layout: what a type builds on sits left of it, what it owns sits right of it.
 * Pinned blocks keep their positions; everything else avoids them.
 */
object LayoutEngine {
    const val H_GAP = 96
    const val V_GAP = 24
    const val PIN_GAP = 16
    const val MAX_COLUMN_HEIGHT = 1400

    private val STRUCTURAL = setOf(LinkKind.EXTENDS, LinkKind.IMPLEMENTS, LinkKind.USES, LinkKind.INJECTS, LinkKind.OWNS)

    fun layout(model: BlockModel, sizeOf: (Block) -> Size, pins: Map<String, Point> = emptyMap()): Layout {
        val rank = ranks(model)
        val columns = orderedColumns(model, rank)
        val auto = place(columns.map { lane -> lane.filter { it.id !in pins } }, sizeOf)
        val pinned = model.blocks.filter { it.id in pins }.associate { block ->
            val size = sizeOf(block)
            val at = pins.getValue(block.id)
            block.id to Rect(at.x, at.y, size.width, size.height)
        }
        return Layout(applyPins(model, auto, pinned))
    }

    /** Longest-path ranks over structural links, compacted so sources sit next to what they feed. */
    private fun ranks(model: BlockModel): Map<String, Int> {
        val edges = model.links.filter { it.kind in STRUCTURAL }
        val rank = model.blocks.associate { it.id to 0 }.toMutableMap()
        for (pass in model.blocks.indices) {
            var changed = false
            for (edge in edges) {
                val wanted = rank.getValue(edge.from) + 1
                if (rank.getValue(edge.to) < wanted) {
                    rank[edge.to] = wanted
                    changed = true
                }
            }
            if (!changed) break
        }
        for (pass in model.blocks.indices) {
            var changed = false
            for (block in model.blocks) {
                val next = edges.filter { it.from == block.id }.minOfOrNull { rank.getValue(it.to) } ?: continue
                if (next - 1 > rank.getValue(block.id)) {
                    rank[block.id] = next - 1
                    changed = true
                }
            }
            if (!changed) break
        }
        model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            model.ofKind(BlockKind.HEADER).forEach { rank[it.id] = rank.getValue(cls.id) }
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

        model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            val column = columns[rank.getValue(cls.id)]
            val headers = column.filter { it.kind == BlockKind.HEADER }
            column.removeAll(headers)
            column.addAll(column.indexOf(cls), headers)
        }
        return columns
    }

    private fun reorder(column: MutableList<Block>, neighbours: (Block) -> List<Int>) {
        val sorted = column.withIndex()
            .sortedWith(compareBy({ neighbours(it.value).takeIf { n -> n.isNotEmpty() }?.average() ?: it.index.toDouble() }, { it.index }))
            .map { it.value }
        column.clear()
        column.addAll(sorted)
    }

    /** Top-aligned lanes, left to right; a lane only grows downwards. */
    private fun place(columns: List<List<Block>>, sizeOf: (Block) -> Size): Map<String, Rect> {
        val stacks = columns.flatMap { wrap(it, sizeOf) }.filter { it.isNotEmpty() }
        val rects = linkedMapOf<String, Rect>()
        var x = 0
        for (stack in stacks) {
            var y = 0
            var width = 0
            for (block in stack) {
                val size = sizeOf(block)
                rects[block.id] = Rect(x, y, size.width, size.height)
                y += size.height + V_GAP
                width = maxOf(width, size.width)
            }
            x += width + H_GAP
        }
        return rects
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
        val result = pinned.toMutableMap()
        val placed = pinned.values.toMutableList()
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
