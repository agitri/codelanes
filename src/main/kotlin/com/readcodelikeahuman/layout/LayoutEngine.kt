package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

data class Size(val width: Int, val height: Int)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val centerY: Int get() = y + height / 2
}

data class Point(val x: Int, val y: Int)

data class Arrow(val link: Link, val from: Point, val to: Point)

data class Layout(val rects: Map<String, Rect>, val arrows: List<Arrow>)

/** Deterministic three-column layout: related types | header + class | methods. */
object LayoutEngine {
    const val H_GAP = 80
    const val V_GAP = 24

    private val LEFT_KINDS = listOf(BlockKind.INTERFACE, BlockKind.PARENT, BlockKind.TRAIT, BlockKind.DEPENDENCY)
    private val CENTER_KINDS = listOf(BlockKind.HEADER, BlockKind.CLASS)
    private val RIGHT_KINDS = listOf(BlockKind.METHOD)

    fun layout(model: BlockModel, sizeOf: (Block) -> Size): Layout {
        val columns = listOf(LEFT_KINDS, CENTER_KINDS, RIGHT_KINDS).map { kinds -> kinds.flatMap(model::ofKind) }
        val rects = linkedMapOf<String, Rect>()
        var x = 0
        for (column in columns) {
            if (column.isEmpty()) continue
            var y = 0
            var width = 0
            for (block in column) {
                val size = sizeOf(block)
                rects[block.id] = Rect(x, y, size.width, size.height)
                y += size.height + V_GAP
                width = maxOf(width, size.width)
            }
            x += width + H_GAP
        }
        val arrows = model.links.map { link ->
            val from = rects.getValue(link.from)
            val to = rects.getValue(link.to)
            val end = if (link.kind == LinkKind.CALLS) Point(to.right, to.centerY) else Point(to.x, to.centerY)
            Arrow(link, Point(from.right, from.centerY), end)
        }
        return Layout(rects, arrows)
    }
}
