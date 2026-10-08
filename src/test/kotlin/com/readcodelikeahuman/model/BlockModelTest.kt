package com.readcodelikeahuman.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockModelTest {
    private fun block(id: String, kind: BlockKind, range: SourceRange = SourceRange(0, 1)) =
        Block(id = id, kind = kind, title = id, filePath = "/Foo.php", range = range)

    @Test
    fun sourceRangeIsHalfOpen() {
        val r = SourceRange(2, 5)
        assertTrue(r.contains(2))
        assertTrue(r.contains(4))
        assertFalse(r.contains(5))
    }

    @Test
    fun sourceRangeRejectsNegativeOrInverted() {
        assertThrows(IllegalArgumentException::class.java) { SourceRange(-1, 3) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange(5, 3) }
    }

    @Test
    fun excludedRangesMustBeInsideBlockRange() {
        assertThrows(IllegalArgumentException::class.java) {
            Block("class:Foo", BlockKind.CLASS, "class Foo", "/Foo.php", SourceRange(10, 20), excluded = listOf(SourceRange(5, 12)))
        }
    }

    @Test
    fun blockIdsMustBeUnique() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockModel(listOf(block("a", BlockKind.METHOD), block("a", BlockKind.METHOD)), emptyList())
        }
    }

    @Test
    fun linksMustPointToExistingBlocks() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockModel(listOf(block("a", BlockKind.CLASS)), listOf(Link(LinkKind.OWNS, "a", "missing")))
        }
    }

    @Test
    fun lookupByIdAndKindKeepsOrder() {
        val model = BlockModel(
            listOf(block("class:Foo", BlockKind.CLASS), block("method:b", BlockKind.METHOD), block("method:a", BlockKind.METHOD)),
            emptyList(),
        )
        assertEquals(BlockKind.CLASS, model.block("class:Foo").kind)
        assertEquals(listOf("method:b", "method:a"), model.ofKind(BlockKind.METHOD).map { it.id })
    }
}
