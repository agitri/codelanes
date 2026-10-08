package com.readcodelikeahuman.layout

/** Where a line between two blocks starts and ends. */
object ArrowGeometry {
    fun connect(from: Rect, to: Rect): Pair<Point, Point> = when {
        to.x >= from.right -> Point(from.right, from.centerY) to Point(to.x, to.centerY)
        to.right <= from.x -> Point(from.x, from.centerY) to Point(to.right, to.centerY)
        else -> Point(from.right, from.centerY) to Point(to.right, to.centerY)
    }
}
