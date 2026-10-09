package dev.codelanes.layout

import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

/** Decides the line for every link: simple elbows where possible, routed around blocks where not. */
object LinkRoutes {
    fun compute(
        links: List<Link>,
        rects: Map<String, Rect>,
        loop: Int = ArrowGeometry.LOOP,
        slotStep: Int = 12,
        targetY: Map<Link, Int> = emptyMap(),
    ): Map<Link, List<Point>> {
        val incoming = links.filter { it.kind != LinkKind.OVERRIDES }.groupBy { it.to }
        // Different kinds of lines leaving the same block get their own exit height, so they never merge.
        val exitKinds = links.filter { it.kind != LinkKind.OVERRIDES }.groupBy { it.from }.mapValues { (_, out) -> out.map { it.kind }.distinct().sorted() }
        val routes = linkedMapOf<Link, List<Point>>()
        for (link in links) {
            val source = rects[link.from] ?: continue
            val kinds = exitKinds[link.from].orEmpty()
            // Same left/right edges, but a zero-height rect at this kind's exit height: routes start there.
            val from = if (kinds.size > 1 && link.kind in kinds) {
                Rect(source.x, source.y + source.height * (kinds.indexOf(link.kind) + 1) / (kinds.size + 1), source.width, 0)
            } else {
                source
            }
            val to = rects[link.to] ?: continue
            val obstacles = rects.filterKeys { it != link.from && it != link.to }.values.toList()
            // A line may touch its own two blocks at their edges, but never run through them.
            val walls = obstacles + source + to
            val ownInsides = listOf(source, to).map { Rect(it.x + 1, it.y + 1, maxOf(0, it.width - 2), maxOf(0, it.height - 2)) }
            routes[link] = if (link.kind == LinkKind.OVERRIDES) {
                aroundBlocks(from, to, targetY[link] ?: to.centerY, walls)
            } else {
                val siblings = incoming.getValue(link.to).sortedBy { rects[it.from]?.y ?: 0 }
                val sameLane = to.x < from.right && to.right > from.x
                val simple = if (siblings.size > 1 && sameLane) {
                    ArrowGeometry.loopIntoSlot(from, to, siblings.indexOf(link), siblings.size, loop, slotStep)
                } else if (siblings.size > 1) {
                    ArrowGeometry.routeIntoSlot(from, to, siblings.indexOf(link), siblings.size, slotStep)
                } else {
                    ArrowGeometry.route(from, to, loop)
                }
                if ((obstacles + ownInsides).none { crosses(simple, it) }) simple else reroute(simple, walls)
            }
        }
        return routes
    }

    /**
     * A line between two blocks that leaves and enters on the sides facing each other, around anything in between.
     * It enters [to] at [entryY] (e.g. the line of the overridden method inside an expanded parent).
     */
    private fun aroundBlocks(from: Rect, to: Rect, entryY: Int, obstacles: List<Rect>): List<Point> {
        val y = entryY.coerceIn(to.y, to.bottom)
        return if (to.centerX < from.centerX) {
            OrthogonalRouter.route(Point(from.x, from.centerY), Direction.LEFT, Point(to.right, y), Direction.LEFT, obstacles)
        } else {
            OrthogonalRouter.route(Point(from.right, from.centerY), Direction.RIGHT, Point(to.x, y), Direction.RIGHT, obstacles)
        }
    }

    /** Keeps the simple route's end points and directions, but finds a way around the blocks in between. */
    private fun reroute(simple: List<Point>, obstacles: List<Rect>): List<Point> =
        OrthogonalRouter.route(simple.first(), directionOf(simple[0], simple[1]), simple.last(), directionOf(simple[simple.size - 2], simple.last()), obstacles)

    private fun directionOf(a: Point, b: Point): Direction = when {
        b.x > a.x -> Direction.RIGHT
        b.x < a.x -> Direction.LEFT
        b.y > a.y -> Direction.DOWN
        else -> Direction.UP
    }

    private fun crosses(route: List<Point>, r: Rect): Boolean = route.zipWithNext().any { (a, b) ->
        maxOf(a.x, b.x) > r.x && minOf(a.x, b.x) < r.right && maxOf(a.y, b.y) > r.y && minOf(a.y, b.y) < r.bottom
    }
}
