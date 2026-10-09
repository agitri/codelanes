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
        canvas.focusedId = "total" // call lines only show (and get their direction) for the focused method
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

    private fun callsAroundTotal(): Pair<BlocksCanvas, List<Link>> {
        val canvas = BlocksCanvas(NoListener)
        val links = listOf(
            Link(LinkKind.CALLS, "total", "subtotal"),
            Link(LinkKind.CALLS, "render", "total"),
            Link(LinkKind.CALLS, "validate", "total"),
        )
        canvas.setContent(listOf("total", "subtotal", "render", "validate").associateWith { BlockView(it, canvas) }, links)
        canvas.focusedId = "total"
        return canvas to links
    }

    fun testCallingAndCalledByUseDifferentColourFamilies() {
        val (canvas, links) = callsAroundTotal()
        val out = canvas.colorFor(links[0])
        val inFromRender = canvas.colorFor(links[1])
        val inFromValidate = canvas.colorFor(links[2])
        assertTrue(BlocksCanvas.CALLS_OUT.contains(out))
        assertTrue(BlocksCanvas.CALLS_IN.contains(inFromRender))
        assertTrue(BlocksCanvas.CALLS_IN.contains(inFromValidate))
        assertFalse(inFromRender == inFromValidate)
    }

    fun testNoLineUsesAHighlightColour() {
        val highlight = setOf(BlockView.DEFINITION_COLOR, BlockView.USAGE_COLOR)
        val lineColours = BlocksCanvas.CALLS_OUT + BlocksCanvas.CALLS_IN + BlocksCanvas.OVERRIDE_PALETTE +
            LinkKind.entries.map { BlocksCanvas(NoListener).colorFor(Link(it, "a", "b")) }
        fun distance(a: java.awt.Color, b: java.awt.Color): Double {
            val dr = (a.red - b.red).toDouble(); val dg = (a.green - b.green).toDouble(); val db = (a.blue - b.blue).toDouble()
            return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }
        for (line in lineColours) for (h in highlight) {
            assertTrue("$line looks like highlight colour $h", distance(line, h) > 100)
        }
    }

    fun testCallLinesAreLabelledFromTheFocusedMethodsPointOfView() {
        val (canvas, links) = callsAroundTotal()
        assertEquals("calls", canvas.labelFor(links[0]))
        assertEquals("called by", canvas.labelFor(links[1]))
        assertNull(canvas.labelFor(Link(LinkKind.OWNS, "a", "b")))
    }

    fun testStructuralLinesSayWhatTheyAre() {
        val canvas = BlocksCanvas(NoListener)
        assertEquals("extends", canvas.labelFor(Link(LinkKind.EXTENDS, "parent", "class")))
        assertEquals("implements", canvas.labelFor(Link(LinkKind.IMPLEMENTS, "interface", "class")))
        assertEquals("uses trait", canvas.labelFor(Link(LinkKind.USES, "trait", "class")))
        assertEquals("injected", canvas.labelFor(Link(LinkKind.INJECTS, "dep", "class")))
        assertEquals("implemented by", canvas.labelFor(Link(LinkKind.IMPLEMENTED_BY, "interface", "impl")))
        assertNull(canvas.labelFor(Link(LinkKind.OWNS, "class", "method")))
    }

    fun testFollowedCallLinesAreAlwaysShownAndLabelled() {
        val canvas = BlocksCanvas(NoListener)
        val link = Link(LinkKind.CALLS_INTO, "method:render", "callee:Repo::find")
        canvas.setContent(listOf("method:render", "callee:Repo::find").associateWith { BlockView(it, canvas) }, listOf(link))
        assertEquals(listOf(link), canvas.visibleLinks())
        assertEquals("calls", canvas.labelFor(link))
    }

    fun testWhatAClassBuildsOnIsPointedAtFromTheClass() {
        val canvas = BlocksCanvas(NoListener)
        val route = listOf(Point(0, 0), Point(50, 0), Point(50, 100), Point(100, 100))
        for (kind in listOf(LinkKind.EXTENDS, LinkKind.IMPLEMENTS, LinkKind.USES, LinkKind.INJECTS)) {
            assertEquals("$kind", route.reversed(), canvas.drawnRoute(Link(kind, "related", "class"), route))
        }
        for (kind in listOf(LinkKind.OWNS, LinkKind.CALLS, LinkKind.IMPLEMENTED_BY, LinkKind.CALLS_INTO)) {
            assertEquals("$kind", route, canvas.drawnRoute(Link(kind, "a", "b"), route))
        }
    }

    fun testANoteIsShownAsTextNeverAsHtml() {
        val view = BlockView("a", BlocksCanvas(NoListener))
        view.setReview(null, "<html><img src='http://example.com/x.png'>\nsecond line")
        assertFalse(view.noteLabelText().contains("<img"))
        assertTrue(view.noteLabelText().contains("&lt;img"))
        assertTrue(view.noteLabelText().contains("<br>second line"))
    }
}
