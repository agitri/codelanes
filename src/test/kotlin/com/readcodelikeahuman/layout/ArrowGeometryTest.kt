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
}
