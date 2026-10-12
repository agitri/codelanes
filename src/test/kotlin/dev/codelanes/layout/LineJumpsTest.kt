package dev.codelanes.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class LineJumpsTest {
    private val vertical = listOf(Point(50, 0), Point(50, 200))

    @Test
    fun aHorizontalLineJumpsWhereAVerticalLineCrossesIt() {
        val route = listOf(Point(0, 100), Point(300, 100))
        assertEquals(listOf(Point(50, 100)), LineJumps.jumps(route, listOf(vertical), radius = 4))
    }

    @Test
    fun theVerticalLineDoesNotJumpTooSoEachCrossingGetsOneJump() {
        val horizontal = listOf(Point(0, 100), Point(300, 100))
        assertEquals(emptyList<Point>(), LineJumps.jumps(vertical, listOf(horizontal), radius = 4))
    }

    @Test
    fun linesThatOnlyTouchAtAnEndOrCornerDoNotJump() {
        val endsOnTheLine = listOf(Point(0, 100), Point(50, 100))
        val startsNextToACorner = listOf(Point(48, 100), Point(300, 100))
        assertEquals(emptyList<Point>(), LineJumps.jumps(endsOnTheLine, listOf(vertical), radius = 4))
        assertEquals(emptyList<Point>(), LineJumps.jumps(startsNextToACorner, listOf(vertical), radius = 4))
    }

    @Test
    fun jumpsComeInTheOrderTheLineRunsAndOnEverySegment() {
        val leftThenDownThenRight = listOf(Point(300, 100), Point(0, 100), Point(0, 150), Point(300, 150))
        val crossers = listOf(vertical, listOf(Point(200, 0), Point(200, 200)))
        assertEquals(
            listOf(Point(200, 100), Point(50, 100), Point(50, 150), Point(200, 150)),
            LineJumps.jumps(leftThenDownThenRight, crossers, radius = 4),
        )
    }

    @Test
    fun linesRunningAlongsideEachOtherDoNotJump() {
        val route = listOf(Point(0, 100), Point(300, 100))
        assertEquals(emptyList<Point>(), LineJumps.jumps(route, listOf(listOf(Point(20, 100), Point(80, 100))), radius = 4))
    }
}
