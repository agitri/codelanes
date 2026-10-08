package dev.codelanes.canvas

import dev.codelanes.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageHighlightTest {
    private val blocks = listOf(
        UsageHighlight.Span("class:Foo", "/Foo.php", SourceRange(0, 300), listOf(SourceRange(100, 200), SourceRange(200, 290))),
        UsageHighlight.Span("method:bar", "/Foo.php", SourceRange(100, 200)),
        UsageHighlight.Span("method:footest", "/Foo.php", SourceRange(200, 290)),
        UsageHighlight.Span("interface:Barro", "/Barro.php", SourceRange(0, 80)),
    )

    @Test
    fun usagesAndTheDefinitionMarkTheirBlocks() {
        val result = UsageHighlight.assign(
            listOf(
                UsageHighlight.Spot("/Foo.php", 150, definition = false),
                UsageHighlight.Spot("/Foo.php", 210, definition = true),
                UsageHighlight.Spot("/Foo.php", 50, definition = false),
            ),
            blocks,
        )
        assertEquals(
            mapOf(
                "method:bar" to UsageHighlight.Level.USAGE,
                "method:footest" to UsageHighlight.Level.DEFINITION,
                "class:Foo" to UsageHighlight.Level.USAGE,
            ),
            result,
        )
    }

    @Test
    fun spotsInOtherFilesAndOutsideAnyBlockAreHandled() {
        val result = UsageHighlight.assign(
            listOf(UsageHighlight.Spot("/Barro.php", 10, definition = true), UsageHighlight.Spot("/Elsewhere.php", 5, definition = false)),
            blocks,
        )
        assertEquals(mapOf("interface:Barro" to UsageHighlight.Level.DEFINITION), result)
    }

    @Test
    fun definitionWinsOverUsageInTheSameBlock() {
        val result = UsageHighlight.assign(
            listOf(UsageHighlight.Spot("/Foo.php", 150, definition = false), UsageHighlight.Spot("/Foo.php", 160, definition = true)),
            blocks,
        )
        assertEquals(mapOf("method:bar" to UsageHighlight.Level.DEFINITION), result)
    }
}
