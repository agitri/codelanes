package dev.codelanes.layout

import java.util.PriorityQueue
import kotlin.math.abs

enum class Direction(val dx: Int, val dy: Int) { LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1) }

/**
 * Finds a line made of horizontal and vertical segments that goes around blocks instead of through them.
 * A* over a sparse grid built from the obstacle edges (plus a margin), with a penalty per bend so routes stay simple.
 */
object OrthogonalRouter {
    const val MARGIN = 16
    private const val BEND_COST = 60

    /**
     * Route from [start] (leaving in [startDir]) to [end] (arriving while moving in [endDir]). The blocks at both
     * ends must not be in [obstacles].
     */
    fun route(start: Point, startDir: Direction, end: Point, endDir: Direction, obstacles: List<Rect>, margin: Int = MARGIN): List<Point> {
        val walls = obstacles.map { Rect(it.x - margin, it.y - margin, it.width + 2 * margin, it.height + 2 * margin) }
        val from = Point(start.x + startDir.dx * margin, start.y + startDir.dy * margin)
        val to = Point(end.x - endDir.dx * margin, end.y - endDir.dy * margin)

        val xs = (listOf(from.x, to.x) + walls.flatMap { listOf(it.x, it.right) }).distinct().sorted()
        val ys = (listOf(from.y, to.y) + walls.flatMap { listOf(it.y, it.bottom) }).distinct().sorted()
        val path = search(xs, ys, walls, from, startDir, to, endDir) ?: listOf(from, Point(to.x, from.y), to)
        return simplify(listOf(start) + path + end)
    }

    private data class State(val xi: Int, val yi: Int, val dir: Direction)

    private fun search(xs: List<Int>, ys: List<Int>, walls: List<Rect>, from: Point, startDir: Direction, to: Point, endDir: Direction): List<Point>? {
        val start = State(xs.indexOf(from.x), ys.indexOf(from.y), startDir)
        val goalX = xs.indexOf(to.x)
        val goalY = ys.indexOf(to.y)
        val best = HashMap<State, Int>()
        val previous = HashMap<State, State>()
        fun heuristic(s: State) = abs(xs[s.xi] - to.x) + abs(ys[s.yi] - to.y)
        val queue = PriorityQueue<Pair<State, Int>>(compareBy { it.second + heuristic(it.first) })
        best[start] = 0
        queue += start to 0

        while (queue.isNotEmpty()) {
            val (state, cost) = queue.poll()
            if (cost > best.getValue(state)) continue
            if (state.xi == goalX && state.yi == goalY) {
                return unwind(state, previous).map { Point(xs[it.xi], ys[it.yi]) }
            }
            for (dir in Direction.entries) {
                if (dir == opposite(state.dir)) continue
                val nx = state.xi + dir.dx
                val ny = state.yi + dir.dy
                if (nx !in xs.indices || ny !in ys.indices) continue
                val a = Point(xs[state.xi], ys[state.yi])
                val b = Point(xs[nx], ys[ny])
                if (!clear(a, b, walls)) continue
                val next = State(nx, ny, dir)
                var nextCost = cost + abs(b.x - a.x) + abs(b.y - a.y) + if (dir != state.dir) BEND_COST else 0
                if (nx == goalX && ny == goalY && dir != endDir) nextCost += BEND_COST
                if (nextCost < (best[next] ?: Int.MAX_VALUE)) {
                    best[next] = nextCost
                    previous[next] = state
                    queue += next to nextCost
                }
            }
        }
        return null
    }

    private fun unwind(end: State, previous: Map<State, State>): List<State> {
        val path = ArrayDeque<State>()
        var current: State? = end
        while (current != null) {
            path.addFirst(current)
            current = previous[current]
        }
        return path
    }

    /** True if the axis-aligned segment a–b doesn't pass through the inside of any wall (touching edges is fine). */
    private fun clear(a: Point, b: Point, walls: List<Rect>): Boolean = walls.none { r ->
        val minX = minOf(a.x, b.x)
        val maxX = maxOf(a.x, b.x)
        val minY = minOf(a.y, b.y)
        val maxY = maxOf(a.y, b.y)
        if (a.y == b.y) a.y > r.y && a.y < r.bottom && maxX > r.x && minX < r.right
        else a.x > r.x && a.x < r.right && maxY > r.y && minY < r.bottom
    }

    private fun opposite(d: Direction) = when (d) {
        Direction.LEFT -> Direction.RIGHT
        Direction.RIGHT -> Direction.LEFT
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
    }

    /** Drops duplicate points and points in the middle of a straight run. */
    private fun simplify(points: List<Point>): List<Point> {
        val distinct = points.fold(mutableListOf<Point>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
        if (distinct.size < 3) return distinct
        val result = mutableListOf(distinct.first())
        for (i in 1 until distinct.size - 1) {
            val a = result.last()
            val b = distinct[i]
            val c = distinct[i + 1]
            val straight = (a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)
            if (!straight) result += b
        }
        result += distinct.last()
        return result
    }
}
