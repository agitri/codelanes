package dev.codelanes.layout

import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LinkRoutesTest {
    private fun crosses(route: List<Point>, r: Rect) = route.zipWithNext().any { (a, b) ->
        maxOf(a.x, b.x) > r.x && minOf(a.x, b.x) < r.right && maxOf(a.y, b.y) > r.y && minOf(a.y, b.y) < r.bottom
    }

    @Test
    fun overrideLinesGoAroundTheClass() {
        val rects = mapOf(
            "method:render" to Rect(1000, 100, 200, 60),
            "class:Order" to Rect(500, 0, 300, 700),
            "interface:Renderable" to Rect(100, 200, 200, 60),
        )
        val link = Link(LinkKind.OVERRIDES, "method:render", "interface:Renderable")
        val route = LinkRoutes.compute(listOf(link), rects).getValue(link)
        assertEquals(Point(1000, 130), route.first())
        assertEquals(Point(300, 230), route.last())
        assertFalse("route $route crosses the class", crosses(route, rects.getValue("class:Order")))
    }

    @Test
    fun aLineThatWouldCrossABlockIsRerouted() {
        val rects = mapOf("a" to Rect(0, 0, 100, 40), "b" to Rect(600, 0, 100, 40), "wall" to Rect(250, -100, 100, 300))
        val link = Link(LinkKind.OWNS, "a", "b")
        val route = LinkRoutes.compute(listOf(link), rects).getValue(link)
        assertFalse("route $route crosses the wall", crosses(route, rects.getValue("wall")))
    }

    @Test
    fun anUnobstructedLineKeepsItsElbow() {
        val rects = mapOf("a" to Rect(0, 0, 100, 40), "b" to Rect(300, 100, 100, 40))
        val link = Link(LinkKind.OWNS, "a", "b")
        assertEquals(ArrowGeometry.route(rects.getValue("a"), rects.getValue("b")), LinkRoutes.compute(listOf(link), rects).getValue(link))
    }

    @Test
    fun anOverrideLineLandsOnTheGivenLineInsideTheTarget() {
        val rects = mapOf("method:validate" to Rect(1000, 100, 200, 60), "parent:Model" to Rect(100, 0, 300, 400))
        val link = Link(LinkKind.OVERRIDES, "method:validate", "parent:Model")
        val route = LinkRoutes.compute(listOf(link), rects, targetY = mapOf(link to 250)).getValue(link)
        assertEquals(Point(400, 250), route.last())
    }

    @Test
    fun loopsIntoTheSameBlockGetTheirOwnVerticalAndEntry() {
        val rects = mapOf("total" to Rect(0, 0, 300, 100), "render" to Rect(0, 300, 300, 100), "validate" to Rect(0, 500, 300, 100))
        val fromRender = Link(LinkKind.CALLS, "render", "total")
        val fromValidate = Link(LinkKind.CALLS, "validate", "total")
        val routes = LinkRoutes.compute(listOf(fromRender, fromValidate), rects)
        val a = routes.getValue(fromRender)
        val b = routes.getValue(fromValidate)
        assertFalse("same vertical: $a / $b", a[1].x == b[1].x)
        assertFalse("same entry: $a / $b", a.last() == b.last())
    }

    @Test
    fun differentKindsOfLinesLeaveABlockAtDifferentPoints() {
        val rects = mapOf(
            "class" to Rect(0, 0, 300, 400),
            "method" to Rect(500, 0, 200, 60),
            "implementer" to Rect(900, 300, 200, 60),
        )
        val owns = Link(LinkKind.OWNS, "class", "method")
        val implementedBy = Link(LinkKind.IMPLEMENTED_BY, "class", "implementer")
        val routes = LinkRoutes.compute(listOf(owns, implementedBy), rects)
        assertFalse("both lines start at ${routes.getValue(owns).first()}", routes.getValue(owns).first() == routes.getValue(implementedBy).first())
    }

    private fun inside(r: Rect) = Rect(r.x + 1, r.y + 1, r.width - 2, r.height - 2)

    @Test
    fun aLineNeverRunsThroughItsOwnStartOrEndBlock() {
        // render() was dragged under the class's right edge
        // render() was dragged under the class's right edge; other methods sit in the lane to the right
        val rects = mapOf(
            "class" to Rect(0, 0, 300, 400),
            "log" to Rect(400, 150, 500, 100),
            "render" to Rect(250, 600, 600, 100),
        )
        val link = Link(LinkKind.OWNS, "class", "render")
        val route = LinkRoutes.compute(listOf(link), rects).getValue(link)
        assertFalse("route $route runs through render", crosses(route, inside(rects.getValue("render"))))
        assertFalse("route $route runs through the class", crosses(route, inside(rects.getValue("class"))))
    }
}
