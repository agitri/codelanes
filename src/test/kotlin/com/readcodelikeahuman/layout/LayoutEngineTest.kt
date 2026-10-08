package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import com.readcodelikeahuman.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutEngineTest {
    private fun block(id: String, kind: BlockKind) =
        Block(id = id, kind = kind, title = id, filePath = "/Foo.php", range = SourceRange(0, 1))

    private val sizeOf: (Block) -> Size = { if (it.kind == BlockKind.CLASS) Size(200, 120) else Size(100, 50) }

    private val fullModel = BlockModel(
        blocks = listOf(
            block("dependency:Repo", BlockKind.DEPENDENCY),
            block("header", BlockKind.HEADER),
            block("class:Foo", BlockKind.CLASS),
            block("method:bar", BlockKind.METHOD),
            block("method:footest", BlockKind.METHOD),
            block("interface:Barro", BlockKind.INTERFACE),
        ),
        links = listOf(
            Link(LinkKind.IMPLEMENTS, "interface:Barro", "class:Foo"),
            Link(LinkKind.INJECTS, "dependency:Repo", "class:Foo"),
            Link(LinkKind.OWNS, "class:Foo", "method:bar"),
            Link(LinkKind.CALLS, "method:bar", "method:footest"),
        ),
    )

    @Test
    fun placesBlocksInThreeColumns() {
        val rects = LayoutEngine.layout(fullModel, sizeOf).rects
        // left column: interfaces before dependencies, regardless of model order
        assertEquals(Rect(0, 0, 100, 50), rects["interface:Barro"])
        assertEquals(Rect(0, 74, 100, 50), rects["dependency:Repo"])
        // center column at x = 100 + 80
        assertEquals(Rect(180, 0, 100, 50), rects["header"])
        assertEquals(Rect(180, 74, 200, 120), rects["class:Foo"])
        // right column at x = 180 + 200 + 80
        assertEquals(Rect(460, 0, 100, 50), rects["method:bar"])
        assertEquals(Rect(460, 74, 100, 50), rects["method:footest"])
    }

    @Test
    fun anchorsArrowsOnBlockEdges() {
        val arrows = LayoutEngine.layout(fullModel, sizeOf).arrows.associate { it.link.kind to (it.from to it.to) }
        assertEquals(Point(100, 25) to Point(180, 134), arrows[LinkKind.IMPLEMENTS])
        assertEquals(Point(100, 99) to Point(180, 134), arrows[LinkKind.INJECTS])
        assertEquals(Point(380, 134) to Point(460, 25), arrows[LinkKind.OWNS])
        assertEquals(Point(560, 25) to Point(560, 99), arrows[LinkKind.CALLS])
    }

    @Test
    fun emptyLeftColumnTakesNoSpace() {
        val model = BlockModel(
            listOf(block("class:Foo", BlockKind.CLASS), block("method:bar", BlockKind.METHOD)),
            emptyList(),
        )
        val rects = LayoutEngine.layout(model, sizeOf).rects
        assertEquals(Rect(0, 0, 200, 120), rects["class:Foo"])
        assertEquals(Rect(280, 0, 100, 50), rects["method:bar"])
    }

    @Test
    fun isDeterministic() {
        assertEquals(LayoutEngine.layout(fullModel, sizeOf), LayoutEngine.layout(fullModel, sizeOf))
    }
}
