package dev.codelanes.editor

import dev.codelanes.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceRangesTest {
    private val foo = """
        <?php
        class Foo
        {
            private string ${'$'}name = 'x';

            public function bar(): void
            {
                echo 1;
            }

            public function footest(): void
            {
            }
        }

    """.trimIndent()

    private val big = """
        <?php
        class Big
        {
            public function m1(): int { return 1; }
            public function m2(): int { return ${'$'}this->m1() + 1; }
            public function m3(): int { return ${'$'}this->m2() + 1; }
        }

    """.trimIndent()

    private fun classRange(text: String) = SourceRange(text.indexOf("class "), text.lastIndexOf('}') + 1)

    /** From "public function <name>" to the end of its closing brace (multi-line or one-line). */
    private fun methodRange(text: String, name: String): SourceRange {
        val start = text.indexOf("public function $name(")
        val lineEnd = text.indexOf('\n', start)
        val oneLiner = text.substring(start, lineEnd).trimEnd().endsWith("}")
        val end = if (oneLiner) text.lastIndexOf('}', lineEnd) + 1 else text.indexOf("\n    }", start) + 6
        return SourceRange(start, end)
    }

    private fun visible(text: String, folds: List<SourceRange>): String =
        text.indices.filter { o -> folds.none { it.contains(o) } }.map { text[it] }.joinToString("")

    @Test
    fun classSliceHidesMethodsAsWholeLines() {
        val excluded = listOf(methodRange(foo, "bar"), methodRange(foo, "footest"))
        val folds = SliceRanges.hidden(foo, classRange(foo), excluded)
        assertEquals("class Foo\n{\n    private string \$name = 'x';\n}", visible(foo, folds))
    }

    @Test
    fun methodSliceShowsOnlyTheMethod() {
        val bar = methodRange(foo, "bar")
        assertEquals(foo.substring(bar.start, bar.end), visible(foo, SliceRanges.hidden(foo, bar, emptyList())))
    }

    @Test
    fun oneLineMethodsLeaveNoHoles() {
        val excluded = listOf("m1", "m2", "m3").map { methodRange(big, it) }
        assertEquals("class Big\n{\n}", visible(big, SliceRanges.hidden(big, classRange(big), excluded)))
    }

    @Test
    fun noReachableCaretPositionLandsOnAMethodLine() {
        for ((text, names) in listOf(foo to listOf("bar", "footest"), big to listOf("m1", "m2", "m3"))) {
            val bounds = classRange(text)
            val excluded = names.map { methodRange(text, it) }
            val folds = SliceRanges.hidden(text, bounds, excluded)
            for (offset in bounds.start..bounds.end) {
                for (previous in listOf(offset - 1, offset + 1)) {
                    val caret = CaretPolicy.adjust(text, bounds, folds, offset, previous)
                    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
                    val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
                    assertTrue(
                        "caret $caret (from $offset) sits on a method line",
                        (lineStart until lineEnd).none { o -> excluded.any { it.contains(o) } },
                    )
                }
            }
        }
    }

    @Test
    fun caretIsClampedToBounds() {
        val bar = methodRange(foo, "bar")
        val folds = SliceRanges.hidden(foo, bar, emptyList())
        assertEquals(bar.start, CaretPolicy.adjust(foo, bar, folds, 0, 5))
        assertEquals(bar.end, CaretPolicy.adjust(foo, bar, folds, foo.length, 5))
    }

    private val fooComment = foo.replace("\n    }\n}\n", "\n    } // end footest\n}\n")

    private val twoOnOneLine = "<?php\nclass Two\n{\n    public function a(): void {} public function b(): void {}\n}\n"

    private fun assertNoCaretOnMethodLines(text: String, bounds: SourceRange, excluded: List<SourceRange>) {
        val folds = SliceRanges.hidden(text, bounds, excluded)
        for (offset in bounds.start..bounds.end) {
            for (previous in listOf(offset - 1, offset + 1)) {
                val caret = CaretPolicy.adjust(text, bounds, folds, offset, previous)
                val lineStart = text.lastIndexOf('\n', caret - 1) + 1
                val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
                assertTrue(
                    "caret $caret (from $offset, previous $previous) sits on a method line",
                    (lineStart until lineEnd).none { o -> excluded.any { it.contains(o) } },
                )
            }
        }
    }

    @Test
    fun trailingCommentAfterMethodDoesNotOpenItsLine() {
        assertNoCaretOnMethodLines(fooComment, classRange(fooComment), listOf(methodRange(fooComment, "bar"), methodRange(fooComment, "footest")))
    }

    @Test
    fun twoMethodsOnOneLineLeaveThatLineUnreachable() {
        val a = SourceRange(twoOnOneLine.indexOf("public function a"), twoOnOneLine.indexOf("{}") + 2)
        val b = SourceRange(twoOnOneLine.indexOf("public function b"), twoOnOneLine.lastIndexOf("{}") + 2)
        assertNoCaretOnMethodLines(twoOnOneLine, classRange(twoOnOneLine), listOf(a, b))
    }

    @Test
    fun headerSliceNeverRestsOnTheClassLine() {
        val text = "<?php\nnamespace App;\n\nclass Foo\n{\n}\n"
        val header = SourceRange(0, text.indexOf("class Foo"))
        val folds = SliceRanges.hidden(text, header, emptyList())
        for (offset in 0..text.length) {
            for (previous in listOf(offset - 1, offset + 1)) {
                val caret = CaretPolicy.adjust(text, header, folds, offset, previous)
                assertTrue("caret $caret on the class line", caret < header.end)
            }
        }
    }
}
