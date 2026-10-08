package dev.codelanes.canvas

import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RebuildPolicyTest {
    private fun model(id: String) =
        BlockModel(listOf(Block(id, BlockKind.CLASS, id, "/Foo.php", SourceRange(0, 1))), emptyList())

    private val old = model("class:Old")
    private val new = model("class:New")

    @Test
    fun syntaxErrorsKeepThePreviousModel() {
        val update = RebuildPolicy.next(old, BuildResult.Supported(new), hasSyntaxErrors = true)
        assertSame(old, update.model)
        assertEquals(RebuildPolicy.SYNTAX_NOTICE, update.notice)
    }

    @Test
    fun supportedResultReplacesTheModel() {
        assertEquals(CanvasUpdate(new, null), RebuildPolicy.next(old, BuildResult.Supported(new), hasSyntaxErrors = false))
    }

    @Test
    fun unsupportedResultPausesOnThePreviousModel() {
        val update = RebuildPolicy.next(old, BuildResult.Unsupported("File contains code before the class"), false)
        assertEquals(CanvasUpdate(old, "Blocks paused: File contains code before the class"), update)
    }

    @Test
    fun unsupportedWithoutPreviousShowsTheReason() {
        assertEquals(CanvasUpdate(null, "Not a PHP file"), RebuildPolicy.next(null, BuildResult.Unsupported("Not a PHP file"), false))
    }

    @Test
    fun firstBuildWithErrorsStillShowsWhatParsed() {
        assertEquals(CanvasUpdate(new, null), RebuildPolicy.next(null, BuildResult.Supported(new), hasSyntaxErrors = true))
    }
}
