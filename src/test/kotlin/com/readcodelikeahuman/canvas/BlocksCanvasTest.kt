package com.readcodelikeahuman.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.layout.Point
import com.readcodelikeahuman.layout.Rect
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import java.awt.Rectangle

class BlocksCanvasTest : BasePlatformTestCase() {
    private object NoListener : BlocksCanvas.Listener {
        override fun blockMoved(id: String, position: Point) {}
        override fun collapseToggled(id: String) {}
        override fun zoomChanged() {}
    }

    fun testPlaceAppliesPanAndZoom() {
        val canvas = BlocksCanvas(NoListener)
        val view = BlockView("a", canvas)
        canvas.setContent(mapOf("a" to view), emptyList())
        canvas.place(mapOf("a" to Rect(10, 20, 100, 50)))
        assertEquals(Rectangle(50, 60, 100, 50), view.bounds)
        canvas.setZoom(2.0)
        canvas.place(mapOf("a" to Rect(10, 20, 100, 50)))
        assertEquals(Rectangle(60, 80, 200, 100), view.bounds)
        assertEquals(Point(10, 20), canvas.toCanvas(java.awt.Point(60, 80)))
    }

    fun testZoomIsClamped() {
        val canvas = BlocksCanvas(NoListener)
        canvas.setZoom(10.0)
        assertEquals(2.0, canvas.zoom)
        canvas.setZoom(0.1)
        assertEquals(0.5, canvas.zoom)
    }

    fun testCallsAreOnlyShownForTheFocusedBlock() {
        val canvas = BlocksCanvas(NoListener)
        val owns = Link(LinkKind.OWNS, "c", "a")
        val calls = Link(LinkKind.CALLS, "a", "b")
        canvas.setContent(listOf("a", "b", "c").associateWith { BlockView(it, canvas) }, listOf(owns, calls))
        assertEquals(listOf(owns), canvas.visibleLinks())
        canvas.focusedId = "b"
        assertEquals(listOf(owns, calls), canvas.visibleLinks())
    }
}
