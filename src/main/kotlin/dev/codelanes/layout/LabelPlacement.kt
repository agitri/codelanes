package dev.codelanes.layout

import kotlin.math.abs

/** Finds a spot for a small label just above a horizontal line segment, where no other line runs through it. */
object LabelPlacement {
    private const val START_GAP = 6
    private const val STEP = 4
    private const val CLEARANCE = 2

    /**
     * Text position (left x, baseline y) for a [width]×[height] label above the segment [from]→[to], as close to
     * [from] as possible without any of [lines] crossing it; null if the segment has no free stretch.
     */
    fun place(from: Point, to: Point, width: Int, height: Int, lines: List<Pair<Point, Point>>): Point? {
        if (from.y != to.y) return null
        val goingRight = to.x >= from.x
        val length = abs(to.x - from.x)
        val top = from.y - height - 4
        var offset = START_GAP
        while (offset + width <= length) {
            val left = if (goingRight) from.x + offset else from.x - offset - width
            val box = Rect(left - CLEARANCE, top, width + 2 * CLEARANCE, from.y - top)
            if (lines.none { (a, b) -> crosses(a, b, box) }) return Point(left, from.y - 4)
            offset += STEP
        }
        return null
    }

    private fun crosses(a: Point, b: Point, r: Rect): Boolean =
        maxOf(a.x, b.x) >= r.x && minOf(a.x, b.x) <= r.right && maxOf(a.y, b.y) >= r.y && minOf(a.y, b.y) < r.bottom
}
