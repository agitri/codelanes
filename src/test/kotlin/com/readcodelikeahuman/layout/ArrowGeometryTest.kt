package com.readcodelikeahuman.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowGeometryTest {
    private val a = Rect(0, 0, 100, 40)

    @Test
    fun targetToTheRight() {
        assertEquals(Point(100, 20) to Point(200, 120), ArrowGeometry.connect(a, Rect(200, 100, 100, 40)))
    }

    @Test
    fun targetToTheLeft() {
        assertEquals(Point(300, 20) to Point(100, 120), ArrowGeometry.connect(Rect(300, 0, 100, 40), Rect(0, 100, 100, 40)))
    }

    @Test
    fun sameColumnLoopsOnTheRightSide() {
        assertEquals(Point(100, 20) to Point(100, 120), ArrowGeometry.connect(a, Rect(0, 100, 100, 40)))
    }
}
