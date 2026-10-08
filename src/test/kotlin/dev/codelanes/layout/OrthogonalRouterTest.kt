package dev.codelanes.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrthogonalRouterTest {
    private fun assertOrthogonal(route: List<Point>) =
        route.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b is diagonal", a.x == b.x || a.y == b.y) }

    private fun crosses(route: List<Point>, r: Rect) = route.zipWithNext().any { (a, b) ->
        val minX = minOf(a.x, b.x); val maxX = maxOf(a.x, b.x); val minY = minOf(a.y, b.y); val maxY = maxOf(a.y, b.y)
        maxX > r.x && minX < r.right && maxY > r.y && minY < r.bottom
    }

    @Test
    fun goesAroundABlockInTheWay() {
        // from the left edge of a method on the right, to the right edge of an interface on the left,
        // with the class block in between
        val start = Point(1000, 300)
        val end = Point(200, 300)
        val classBlock = Rect(400, 0, 400, 700)
        val route = OrthogonalRouter.route(start, Direction.LEFT, end, Direction.LEFT, listOf(classBlock))
        assertEquals(start, route.first())
        assertEquals(end, route.last())
        assertOrthogonal(route)
        assertFalse("route $route crosses the class", crosses(route, classBlock))
    }

    @Test
    fun straightWhenNothingIsInTheWay() {
        val route = OrthogonalRouter.route(Point(0, 50), Direction.RIGHT, Point(300, 50), Direction.RIGHT, emptyList())
        assertEquals(listOf(Point(0, 50), Point(300, 50)), route)
    }
}
