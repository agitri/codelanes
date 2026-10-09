package dev.codelanes.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.codelanes.layout.Point
import dev.codelanes.layout.Rect
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
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

    fun testOverrideLinesAreOnlyShownForTheFocusedBlock() {
        val canvas = BlocksCanvas(NoListener)
        val overrides = Link(LinkKind.OVERRIDES, "a", "b")
        canvas.setContent(listOf("a", "b", "c").associateWith { BlockView(it, canvas) }, listOf(overrides))
        assertEquals(emptyList<Link>(), canvas.visibleLinks())
        canvas.focusedId = "a"
        assertEquals(listOf(overrides), canvas.visibleLinks())
        canvas.focusedId = "c"
        assertEquals(emptyList<Link>(), canvas.visibleLinks())
    }

    fun testEachOverrideLineGetsItsOwnColour() {
        val canvas = BlocksCanvas(NoListener)
        val first = Link(LinkKind.OVERRIDES, "m", "i1")
        val second = Link(LinkKind.OVERRIDES, "m", "i2")
        canvas.setContent(listOf("m", "i1", "i2").associateWith { BlockView(it, canvas) }, listOf(first, second))
        assertFalse(canvas.colorFor(first) == canvas.colorFor(second))
        assertEquals(canvas.colorFor(first), canvas.colorFor(first))
    }

    fun testInAParentOnlyTheMethodUnderTheCaretShowsItsOverrideLine() {
        val canvas = BlocksCanvas(NoListener)
        val validate = Link(LinkKind.OVERRIDES, "method:validate", "parent:Model", dev.codelanes.model.SourceRange(100, 140))
        val id = Link(LinkKind.OVERRIDES, "method:id", "parent:Model", dev.codelanes.model.SourceRange(50, 90))
        canvas.setContent(listOf("method:validate", "method:id", "parent:Model").associateWith { BlockView(it, canvas) }, listOf(validate, id))
        canvas.focusedId = "parent:Model"
        canvas.focusedOffset = 120
        assertEquals(listOf(validate), canvas.visibleLinks())
        canvas.focusedOffset = 10
        assertEquals(emptyList<Link>(), canvas.visibleLinks())
    }

    fun testEveryLineKindHasItsOwnColourAndIsSolid() {
        val canvas = BlocksCanvas(NoListener)
        val kinds = LinkKind.entries.filter { it != LinkKind.OVERRIDES }
        val colours = kinds.map { canvas.colorFor(Link(it, "a", "b")) }
        assertEquals(kinds.size, colours.toSet().size)
        LinkKind.entries.forEach { assertNull("$it is dashed", canvas.strokeFor(it).dashArray) }
    }

    fun testEachCallLineGetsItsOwnColour() {
        val canvas = BlocksCanvas(NoListener)
        val first = Link(LinkKind.CALLS, "render", "total")
        val second = Link(LinkKind.CALLS, "total", "subtotal")
        canvas.setContent(listOf("render", "total", "subtotal").associateWith { BlockView(it, canvas) }, listOf(first, second))
        assertFalse(canvas.colorFor(first) == canvas.colorFor(second))
    }

    fun testABlockBeingDraggedIsNotSnappedBackByARelayout() {
        val canvas = BlocksCanvas(NoListener)
        val view = BlockView("a", canvas)
        canvas.setContent(mapOf("a" to view), emptyList())
        canvas.place(mapOf("a" to Rect(10, 20, 100, 50)))
        view.setLocation(300, 300)
        view.dragging = true
        canvas.place(mapOf("a" to Rect(10, 20, 100, 50)))
        assertEquals(java.awt.Point(300, 300), view.location)
    }

    fun testHighlightBordersDontShrinkTheBlock() {
        val canvas = BlocksCanvas(NoListener)
        val view = BlockView("a", canvas)
        val plain = view.insets
        view.setHighlight(UsageHighlight.Level.DEFINITION)
        assertEquals(plain, view.insets)
        view.setHighlight(UsageHighlight.Level.USAGE)
        assertEquals(plain, view.insets)
    }

    fun testTidyUpSitsInTheBottomLeftCorner() {
        val canvas = BlocksCanvas(NoListener)
        canvas.setSize(1000, 800)
        canvas.doLayout()
        val button = canvas.tidyButton.bounds
        assertEquals(12, button.x)
        assertEquals(800 - 8, button.y + button.height)
    }
}
