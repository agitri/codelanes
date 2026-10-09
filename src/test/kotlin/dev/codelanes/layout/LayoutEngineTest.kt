package dev.codelanes.layout

import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockKind.CLASS
import dev.codelanes.model.BlockKind.HEADER
import dev.codelanes.model.BlockKind.INTERFACE
import dev.codelanes.model.BlockKind.METHOD
import dev.codelanes.model.BlockKind.PARENT
import dev.codelanes.model.BlockKind.TRAIT
import dev.codelanes.model.BlockKind.DEPENDENCY
import dev.codelanes.model.BlockModel
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind.CALLS
import dev.codelanes.model.LinkKind.EXTENDS
import dev.codelanes.model.LinkKind.IMPLEMENTS
import dev.codelanes.model.LinkKind.OWNS
import dev.codelanes.model.LinkKind.USES
import dev.codelanes.model.LinkKind.INJECTS
import dev.codelanes.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {
    private fun block(id: String, kind: BlockKind) =
        Block(id = id, kind = kind, title = id, filePath = "/Foo.php", range = SourceRange(0, 1))

    private val sizeOf: (Block) -> Size = { if (it.kind == CLASS) Size(300, 160) else Size(200, 60) }

    private val foo = BlockModel(
        listOf(
            block("header", HEADER),
            block("class:Foo", CLASS),
            block("method:bar", METHOD),
            block("method:footest", METHOD),
            block("interface:X", INTERFACE),
            block("interface:Y", INTERFACE),
            block("parent:Base", PARENT),
        ),
        listOf(
            Link(OWNS, "class:Foo", "method:bar"),
            Link(OWNS, "class:Foo", "method:footest"),
            Link(IMPLEMENTS, "interface:X", "class:Foo"),
            Link(IMPLEMENTS, "interface:Y", "class:Foo"),
            Link(EXTENDS, "parent:Base", "class:Foo"),
            Link(CALLS, "method:bar", "method:footest"),
        ),
    )

    private fun assertNoOverlap(rects: Map<String, Rect>) {
        val entries = rects.entries.toList()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            assertFalse("${entries[i].key} overlaps ${entries[j].key}", entries[i].value.overlaps(entries[j].value))
        }
    }

    @Test
    fun relatedTypesLeftOfClassAndMethodsRight() {
        val r = LayoutEngine.layout(foo, sizeOf).rects
        val cls = r.getValue("class:Foo")
        listOf("interface:X", "interface:Y", "parent:Base").forEach { assertTrue(it, r.getValue(it).right <= cls.x) }
        listOf("method:bar", "method:footest").forEach { assertTrue(it, r.getValue(it).x >= cls.right) }
    }

    @Test
    fun headerSitsDirectlyAboveClass() {
        val r = LayoutEngine.layout(foo, sizeOf).rects
        val header = r.getValue("header")
        val cls = r.getValue("class:Foo")
        assertEquals(cls.x, header.x)
        assertTrue(header.bottom <= cls.y)
    }

    @Test
    fun noTwoBlocksOverlap() {
        assertNoOverlap(LayoutEngine.layout(foo, sizeOf).rects)
    }

    @Test
    fun tallMethodColumnWrapsToTheRight() {
        val methods = (1..30).map { block("method:m$it", METHOD) }
        val model = BlockModel(listOf(block("class:A", CLASS)) + methods, methods.map { Link(OWNS, "class:A", it.id) })
        val r = LayoutEngine.layout(model, sizeOf).rects
        val cls = r.getValue("class:A")
        val columns = methods.map { r.getValue(it.id) }.groupBy { it.x }
        assertTrue("expected wrapping, got ${columns.size} column(s)", columns.size >= 2)
        columns.values.forEach { col ->
            assertTrue(col.maxOf { it.bottom } - col.minOf { it.y } <= LayoutEngine.MAX_COLUMN_HEIGHT)
            col.forEach { assertTrue(it.x >= cls.right) }
        }
        assertNoOverlap(r)
    }

    @Test
    fun revealedChainsDoNotCross() {
        val model = BlockModel(
            listOf(
                block("interface:I0", INTERFACE),
                block("parent:G", PARENT),
                block("parent:P", PARENT),
                block("interface:I1", INTERFACE),
                block("class:A", CLASS),
            ),
            listOf(
                Link(EXTENDS, "parent:G", "parent:P"),
                Link(EXTENDS, "interface:I0", "interface:I1"),
                Link(EXTENDS, "parent:P", "class:A"),
                Link(IMPLEMENTS, "interface:I1", "class:A"),
            ),
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        assertTrue(r.getValue("parent:G").right <= r.getValue("parent:P").x)
        assertTrue(r.getValue("interface:I0").right <= r.getValue("interface:I1").x)
        assertTrue(r.getValue("parent:P").right <= r.getValue("class:A").x)
        assertEquals(
            r.getValue("parent:G").y < r.getValue("interface:I0").y,
            r.getValue("parent:P").y < r.getValue("interface:I1").y,
        )
    }

    @Test
    fun pinnedBlockKeepsPositionAndOthersMoveAway() {
        val classPos = LayoutEngine.layout(foo, sizeOf).rects.getValue("class:Foo")
        val pins = mapOf("method:bar" to Point(classPos.x, classPos.y))
        val r = LayoutEngine.layout(foo, sizeOf, pins).rects
        assertEquals(classPos.x, r.getValue("method:bar").x)
        assertEquals(classPos.y, r.getValue("method:bar").y)
        assertNoOverlap(r)
    }

    @Test
    fun newMethodAppendsBelowOthersAndNothingElseMoves() {
        val before = LayoutEngine.layout(foo, sizeOf).rects
        val grown = BlockModel(
            foo.blocks + block("method:added", METHOD),
            foo.links + Link(OWNS, "class:Foo", "method:added"),
        )
        val after = LayoutEngine.layout(grown, sizeOf).rects
        before.forEach { (id, rect) -> assertEquals(id, rect, after.getValue(id)) }
        val added = after.getValue("method:added")
        val footest = after.getValue("method:footest")
        assertEquals(footest.x, added.x)
        assertEquals(footest.bottom + LayoutEngine.V_GAP, added.y)
    }

    @Test
    fun pinnedBlockLeavesNoGapInItsLane() {
        val auto = LayoutEngine.layout(foo, sizeOf).rects
        val r = LayoutEngine.layout(foo, sizeOf, mapOf("method:bar" to Point(2000, 2000))).rects
        assertEquals(auto.getValue("method:bar").y, r.getValue("method:footest").y)
        assertEquals(auto.getValue("method:bar").x, r.getValue("method:footest").x)
    }

    @Test
    fun stalePinsAreIgnored() {
        assertEquals(
            LayoutEngine.layout(foo, sizeOf),
            LayoutEngine.layout(foo, sizeOf, mapOf("method:gone" to Point(5, 5))),
        )
    }

    @Test
    fun isDeterministic() {
        assertEquals(LayoutEngine.layout(foo, sizeOf), LayoutEngine.layout(foo, sizeOf))
    }

    @Test
    fun eachRelatedKindGetsItsOwnLaneInAStaircase() {
        val model = BlockModel(
            listOf(
                block("dependency:D", DEPENDENCY),
                block("trait:T", TRAIT),
                block("interface:I1", INTERFACE),
                block("interface:I2", INTERFACE),
                block("parent:P", PARENT),
                block("class:A", CLASS),
                block("method:m", METHOD),
            ),
            listOf(
                Link(INJECTS, "dependency:D", "class:A"),
                Link(USES, "trait:T", "class:A"),
                Link(IMPLEMENTS, "interface:I1", "class:A"),
                Link(IMPLEMENTS, "interface:I2", "class:A"),
                Link(EXTENDS, "parent:P", "class:A"),
                Link(OWNS, "class:A", "method:m"),
            ),
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        val lanes = listOf("parent:P", "interface:I1", "trait:T", "dependency:D").map(r::getValue)
        // every lane hugs the class: same right edge, one gap away from the class
        lanes.forEach { assertEquals(r.getValue("class:A").x - LayoutEngine.H_GAP, it.right) }
        // each lane sits in its own band, below the previous one, so lines never cross blocks
        assertTrue(r.getValue("parent:P").bottom <= r.getValue("interface:I1").y)
        assertTrue(r.getValue("interface:I2").bottom <= r.getValue("trait:T").y)
        assertTrue(r.getValue("trait:T").bottom <= r.getValue("dependency:D").y)
        assertEquals(r.getValue("interface:I1").x, r.getValue("interface:I2").x)
        // class and methods stay top-aligned
        assertEquals(0, r.getValue("class:A").y)
        assertEquals(0, r.getValue("method:m").y)
        assertNoOverlap(r)
    }

    @Test
    fun methodsThatCallEachOtherSitTogether() {
        val methods = listOf("a", "b", "c", "d").map { block("method:$it", METHOD) }
        val model = BlockModel(
            listOf(block("class:A", CLASS)) + methods,
            methods.map { Link(OWNS, "class:A", it.id) } + Link(CALLS, "method:a", "method:c") + Link(CALLS, "method:c", "method:a"),
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        assertEquals(listOf("method:a", "method:c", "method:b", "method:d"), methods.map { it.id }.sortedBy { r.getValue(it).y })
    }

    @Test
    fun implementersAndTheirMethodsMirrorToTheRight() {
        val model = BlockModel(
            listOf(
                block("class:I", CLASS),
                block("method:render", METHOD),
                block("implementer:Order", BlockKind.IMPLEMENTER),
                block("implementation:Order::render", BlockKind.IMPLEMENTATION),
            ),
            listOf(
                Link(OWNS, "class:I", "method:render"),
                Link(dev.codelanes.model.LinkKind.IMPLEMENTED_BY, "class:I", "implementer:Order"),
                Link(OWNS, "implementer:Order", "implementation:Order::render"),
            ),
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        assertTrue(r.getValue("method:render").right <= r.getValue("implementer:Order").x)
        assertTrue(r.getValue("implementer:Order").right <= r.getValue("implementation:Order::render").x)
        assertNoOverlap(r)
    }

    @Test
    fun draggedBlocksNeverEndUpOnTopOfEachOther() {
        val pins = mapOf("method:bar" to Point(1000, 1000), "method:footest" to Point(1050, 1020))
        val r = LayoutEngine.layout(foo, sizeOf, pins).rects
        assertEquals(Point(1000, 1000), r.getValue("method:bar").let { Point(it.x, it.y) })
        assertNoOverlap(r)
    }

    @Test
    fun followedCallsOpenALaneRightOfTheMethods() {
        val model = BlockModel(
            listOf(block("class:A", CLASS), block("method:render", METHOD), block("callee:Repo::find", BlockKind.CALLEE)),
            listOf(Link(OWNS, "class:A", "method:render"), Link(dev.codelanes.model.LinkKind.CALLS_INTO, "method:render", "callee:Repo::find")),
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        assertTrue(r.getValue("method:render").right <= r.getValue("callee:Repo::find").x)
    }

    @Test
    fun aFollowedChainReadsTopToBottomInOneLane() {
        val callee = BlockKind.CALLEE
        val into = dev.codelanes.model.LinkKind.CALLS_INTO
        val implementedBy = dev.codelanes.model.LinkKind.IMPLEMENTED_BY
        // create → place → (save → DoctrineSave → toRow), send ; deep chain of 30 more to check it never wraps
        val deep = (1..30).map { block("callee:deep$it", callee) }
        val model = BlockModel(
            listOf(
                block("class:C", CLASS),
                block("method:create", METHOD),
                block("callee:place", callee),
                block("callee:send", callee),
                block("callee:save", callee),
                block("callee:doctrineSave", callee),
                block("callee:toRow", callee),
            ) + deep,
            listOf(
                Link(OWNS, "class:C", "method:create"),
                Link(into, "method:create", "callee:place"),
                Link(into, "callee:place", "callee:save"),
                Link(into, "callee:place", "callee:send"),
                Link(implementedBy, "callee:save", "callee:doctrineSave"),
                Link(into, "callee:doctrineSave", "callee:toRow"),
                Link(into, "callee:toRow", "callee:deep1"),
            ) + (1 until 30).map { Link(into, "callee:deep$it", "callee:deep${it + 1}") },
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        val chain = listOf("callee:place", "callee:save", "callee:doctrineSave", "callee:toRow") + deep.map { it.id } + "callee:send"
        assertEquals("one lane", 1, chain.map { r.getValue(it).x }.distinct().size)
        assertTrue(r.getValue("method:create").right <= r.getValue("callee:place").x)
        assertEquals("depth-first call order, top to bottom", chain, chain.sortedBy { r.getValue(it).y })
    }

    @Test
    fun verticalLayoutPutsEverythingInOneColumnTopToBottom() {
        val r = LayoutEngine.layout(foo, sizeOf, mode = LayoutMode.COLUMN).rects
        assertEquals("one column", 1, r.values.map { it.x }.distinct().size)
        val order = listOf("parent:Base", "interface:X", "interface:Y", "header", "class:Foo", "method:bar", "method:footest")
        assertEquals(order, order.sortedBy { r.getValue(it).y })
        assertNoOverlap(r)
    }

    @Test
    fun treeLayoutPutsEachLevelInARowBelowThePrevious() {
        val r = LayoutEngine.layout(foo, sizeOf, mode = LayoutMode.TREE).rects
        val cls = r.getValue("class:Foo")
        listOf("parent:Base", "interface:X", "interface:Y").forEach { assertTrue(it, r.getValue(it).bottom <= cls.y) }
        // methods side by side in one row below the class
        val bar = r.getValue("method:bar")
        val footest = r.getValue("method:footest")
        assertEquals(bar.y, footest.y)
        assertTrue(bar.right <= footest.x || footest.right <= bar.x)
        assertTrue(bar.y >= cls.bottom)
        // parents and interfaces share a row
        assertEquals(r.getValue("parent:Base").y, r.getValue("interface:X").y)
        assertNoOverlap(r)
    }

    @Test
    fun inTheTreeAFollowedChainGoesDownLevelByLevel() {
        val into = dev.codelanes.model.LinkKind.CALLS_INTO
        val model = BlockModel(
            listOf(block("class:C", CLASS), block("method:create", METHOD), block("callee:place", BlockKind.CALLEE), block("callee:save", BlockKind.CALLEE)),
            listOf(Link(OWNS, "class:C", "method:create"), Link(into, "method:create", "callee:place"), Link(into, "callee:place", "callee:save")),
        )
        val r = LayoutEngine.layout(model, sizeOf, mode = LayoutMode.TREE).rects
        assertTrue(r.getValue("method:create").bottom <= r.getValue("callee:place").y)
        assertTrue(r.getValue("callee:place").bottom <= r.getValue("callee:save").y)
    }

    @Test
    fun inTheTreeCallsFromOneMethodSitSideBySideEvenThroughAnInterface() {
        val into = dev.codelanes.model.LinkKind.CALLS_INTO
        val implementedBy = dev.codelanes.model.LinkKind.IMPLEMENTED_BY
        val callee = BlockKind.CALLEE
        val model = BlockModel(
            listOf(
                block("class:C", CLASS), block("method:create", METHOD),
                block("callee:place", callee), block("callee:save", callee), block("callee:send", callee), block("callee:doctrineSave", callee),
            ),
            listOf(
                Link(OWNS, "class:C", "method:create"),
                Link(into, "method:create", "callee:place"),
                Link(into, "callee:place", "callee:save"),
                Link(into, "callee:place", "callee:send"),
                Link(implementedBy, "callee:save", "callee:doctrineSave"),
            ),
        )
        val r = LayoutEngine.layout(model, sizeOf, mode = LayoutMode.TREE).rects
        assertEquals("save and send on one row", r.getValue("callee:save").y, r.getValue("callee:send").y)
        assertTrue(r.getValue("callee:save").bottom <= r.getValue("callee:doctrineSave").y)
        assertTrue(r.getValue("callee:place").bottom <= r.getValue("callee:save").y)
    }
}
