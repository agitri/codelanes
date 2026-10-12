package dev.codelanes.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Where a line hops over the lines it crosses. Only horizontal segments hop, over vertical segments of other lines,
 * so each crossing gets exactly one hop. Crossings within [radius] of a segment's end are left alone: there the line
 * turns a corner and a hop would look like a kink.
 */
object LineJumps {
    /** Hop centres along [route], in the order the line runs. */
    fun jumps(route: List<Point>, others: List<List<Point>>, radius: Int): List<Point> {
        val verticals = others.flatMap { it.zipWithNext() }.filter { (a, b) -> a.x == b.x && a.y != b.y }
        return route.zipWithNext().filter { (a, b) -> a.y == b.y && a.x != b.x }.flatMap { (a, b) ->
            val left = min(a.x, b.x)
            val right = max(a.x, b.x)
            val hops = verticals
                .filter { (c, d) -> c.x > left + radius && c.x < right - radius && a.y > min(c.y, d.y) && a.y < max(c.y, d.y) }
                .map { (c, _) -> c.x }
                .distinct()
                .sorted()
            (if (b.x > a.x) hops else hops.reversed()).map { Point(it, a.y) }
        }
    }
}
