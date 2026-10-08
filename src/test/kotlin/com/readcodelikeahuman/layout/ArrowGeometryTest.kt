package com.readcodelikeahuman.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowGeometryTest {
    private val a = Rect(0, 0, 100, 40)

    @Test
    fun targetToTheRightIsAnElbowThroughTheMiddleOfTheGap() {
        assertEquals(
            listOf(Point(100, 20), Point(150, 20), Point(150, 120), Point(200, 120)),
            ArrowGeometry.route(a, Rect(200, 100, 100, 40)),
        )
    }

    @Test
    fun targetToTheLeftIsAMirroredElbow() {
        assertEquals(
            listOf(Point(300, 20), Point(200, 20), Point(200, 120), Point(100, 120)),
            ArrowGeometry.route(Rect(300, 0, 100, 40), Rect(0, 100, 100, 40)),
        )
    }

    @Test
    fun sameLaneLoopsOutOnTheRightAndComesBackIn() {
        assertEquals(
            listOf(Point(100, 20), Point(124, 20), Point(124, 120), Point(100, 120)),
            ArrowGeometry.route(a, Rect(0, 100, 100, 40), loop = 24),
        )
    }

    @Test
    fun loopClearsTheWiderOfTwoBlocks() {
        assertEquals(Point(224, 20), ArrowGeometry.route(a, Rect(0, 100, 200, 40), loop = 24)[1])
    }

    @Test
    fun incomingLinesGetTheirOwnTrunkAndEntryPoint() {
        val target = Rect(500, 0, 200, 120)
        val first = ArrowGeometry.routeIntoSlot(Rect(0, 0, 100, 40), target, slot = 0, slots = 2, step = 12)
        val second = ArrowGeometry.routeIntoSlot(Rect(0, 200, 100, 40), target, slot = 1, slots = 2, step = 12)
        assertEquals(listOf(Point(100, 20), Point(488, 20), Point(488, 40), Point(500, 40)), first)
        assertEquals(listOf(Point(100, 220), Point(476, 220), Point(476, 80), Point(500, 80)), second)
    }
}
