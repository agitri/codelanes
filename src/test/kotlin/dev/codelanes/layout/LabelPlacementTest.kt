package dev.codelanes.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacementTest {
    @Test
    fun aLabelSlidesPastALineThatCrossesItsSegment() {
        // label along a segment going right from x=0, a vertical line crosses at x=24
        val at = LabelPlacement.place(Point(0, 100), Point(300, 100), width = 50, height = 10, lines = listOf(Point(24, 40) to Point(24, 200)))!!
        assertTrue("label at $at overlaps the vertical line", at.x > 24)
        assertEquals(96, at.y)
    }

    @Test
    fun withNothingInTheWayTheLabelSitsNextToTheBlock() {
        assertEquals(Point(6, 96), LabelPlacement.place(Point(0, 100), Point(300, 100), width = 50, height = 10, lines = emptyList()))
    }

    @Test
    fun goingLeftTheLabelEndsNextToTheBlock() {
        assertEquals(Point(244, 96), LabelPlacement.place(Point(300, 100), Point(0, 100), width = 50, height = 10, lines = emptyList()))
    }

    @Test
    fun noRoomGivesNull() {
        assertNull(LabelPlacement.place(Point(0, 100), Point(30, 100), width = 50, height = 10, lines = emptyList()))
    }
}
