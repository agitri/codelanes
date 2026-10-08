package com.readcodelikeahuman.layout

/** Elbow-shaped routes between blocks: out sideways, along a vertical, and in sideways. */
object ArrowGeometry {
    const val LOOP = 24

    fun route(from: Rect, to: Rect, loop: Int = LOOP): List<Point> = when {
        to.x >= from.right -> elbow(Point(from.right, from.centerY), Point(to.x, to.centerY))
        to.right <= from.x -> elbow(Point(from.x, from.centerY), Point(to.right, to.centerY))
        else -> {
            // Same lane (e.g. a call between stacked methods): loop out on the right and come back in.
            val outX = maxOf(from.right, to.right) + loop
            listOf(Point(from.right, from.centerY), Point(outX, from.centerY), Point(outX, to.centerY), Point(to.right, to.centerY))
        }
    }

    private fun elbow(start: Point, end: Point): List<Point> {
        val midX = (start.x + end.x) / 2
        return listOf(start, Point(midX, start.y), Point(midX, end.y), end)
    }
}
