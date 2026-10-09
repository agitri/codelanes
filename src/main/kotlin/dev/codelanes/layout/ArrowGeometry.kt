package dev.codelanes.layout

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

    /**
     * One of several lines entering [to] from the left: each gets its own vertical trunk and entry point, so
     * lines from different blocks never merge. Slot 0 (the topmost source) uses the trunk nearest to [to].
     */
    fun routeIntoSlot(from: Rect, to: Rect, slot: Int, slots: Int, step: Int): List<Point> {
        if (to.x < from.right) return route(from, to)
        val entryY = to.y + to.height * (slot + 1) / (slots + 1)
        val trunkX = maxOf(from.right, to.x - step * (slot + 1))
        return listOf(Point(from.right, from.centerY), Point(trunkX, from.centerY), Point(trunkX, entryY), Point(to.x, entryY))
    }

    /** One of several loops into the same block in the same lane: each loops a bit further out and enters at its own height. */
    fun loopIntoSlot(from: Rect, to: Rect, slot: Int, slots: Int, loop: Int, step: Int): List<Point> {
        val outX = maxOf(from.right, to.right) + loop + step * slot
        val entryY = to.y + to.height * (slot + 1) / (slots + 1)
        return listOf(Point(from.right, from.centerY), Point(outX, from.centerY), Point(outX, entryY), Point(to.right, entryY))
    }

    /** Tree layout: out of the bottom of [from], into the top of [to] (or the other way round when [to] is above). */
    fun routeDown(from: Rect, to: Rect, loop: Int = LOOP): List<Point> = when {
        to.y >= from.bottom -> verticalElbow(Point(from.centerX, from.bottom), Point(to.centerX, to.y))
        to.bottom <= from.y -> verticalElbow(Point(from.centerX, from.y), Point(to.centerX, to.bottom))
        else -> {
            // Same row: loop out underneath and come back up into the bottom of the target.
            val outY = maxOf(from.bottom, to.bottom) + loop
            listOf(Point(from.centerX, from.bottom), Point(from.centerX, outY), Point(to.centerX, outY), Point(to.centerX, to.bottom))
        }
    }

    /** One of several lines entering [to] from above: its own entry point on the top edge and its own crossing height. */
    fun routeDownIntoSlot(from: Rect, to: Rect, slot: Int, slots: Int, step: Int): List<Point> {
        if (to.y < from.bottom) return routeDown(from, to)
        val entryX = to.x + to.width * (slot + 1) / (slots + 1)
        val crossY = maxOf(from.bottom, to.y - step * (slot + 1))
        return listOf(Point(from.centerX, from.bottom), Point(from.centerX, crossY), Point(entryX, crossY), Point(entryX, to.y))
    }

    private fun verticalElbow(start: Point, end: Point): List<Point> {
        val midY = (start.y + end.y) / 2
        return listOf(start, Point(start.x, midY), Point(end.x, midY), end)
    }

    private fun elbow(start: Point, end: Point): List<Point> {
        val midX = (start.x + end.x) / 2
        return listOf(start, Point(midX, start.y), Point(midX, end.y), end)
    }
}
