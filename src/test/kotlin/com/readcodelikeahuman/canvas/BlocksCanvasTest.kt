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
        override fun tidyUp() {}
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
        canvas.setZoom(0.01)
        assertEquals(0.2, canvas.zoom)
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

    private class Recorder : BlocksCanvas.Listener {
        val moved = mutableListOf<String>()
        val toggled = mutableListOf<String>()
        var tidied = 0
        override fun tidyUp() { tidied++ }
        override fun blockMoved(id: String, position: Point) { moved += id }
        override fun collapseToggled(id: String) { toggled += id }
        override fun zoomChanged() {}
    }

    private fun mouse(view: BlockView, id: Int, x: Int, y: Int, clicks: Int = 0) {
        val bar = view.titleBar
        bar.dispatchEvent(java.awt.event.MouseEvent(bar, id, 0L, 0, x, y, 100 + x, 100 + y, clicks, false, java.awt.event.MouseEvent.BUTTON1))
    }

    fun testSmallWobbleWhileClickingIsAClickNotADrag() {
        val recorder = Recorder()
        val canvas = BlocksCanvas(recorder)
        val view = BlockView("a", canvas)
        canvas.setContent(mapOf("a" to view), emptyList())
        mouse(view, java.awt.event.MouseEvent.MOUSE_PRESSED, 10, 10)
        mouse(view, java.awt.event.MouseEvent.MOUSE_DRAGGED, 12, 11)
        mouse(view, java.awt.event.MouseEvent.MOUSE_RELEASED, 12, 11)
        mouse(view, java.awt.event.MouseEvent.MOUSE_CLICKED, 12, 11, clicks = 1)
        assertEquals(emptyList<String>(), recorder.moved)
        assertEquals(listOf("a"), recorder.toggled)
    }

    fun testRealDragMovesTheBlock() {
        val recorder = Recorder()
        val canvas = BlocksCanvas(recorder)
        val view = BlockView("a", canvas)
        canvas.setContent(mapOf("a" to view), emptyList())
        mouse(view, java.awt.event.MouseEvent.MOUSE_PRESSED, 10, 10)
        mouse(view, java.awt.event.MouseEvent.MOUSE_DRAGGED, 40, 30)
        mouse(view, java.awt.event.MouseEvent.MOUSE_RELEASED, 40, 30)
        assertEquals(listOf("a"), recorder.moved)
        assertEquals(emptyList<String>(), recorder.toggled)
    }

    fun testTidyUpButtonAsksToTidyUp() {
        val recorder = Recorder()
        BlocksCanvas(recorder).tidyButton.doClick()
        assertEquals(1, recorder.tidied)
    }
}
