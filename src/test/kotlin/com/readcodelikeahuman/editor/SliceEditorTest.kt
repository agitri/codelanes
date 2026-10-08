package com.readcodelikeahuman.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.php.PhpBlockBuilder

class SliceEditorTest : BasePlatformTestCase() {
    private val source = """
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

    private fun open(): Pair<SliceEditor, BlockModel> {
        myFixture.configureByText("Foo.php", source)
        val model = (PhpBlockBuilder.build(myFixture.file) as BuildResult.Supported).model
        val slice = SliceEditor(project, myFixture.file.virtualFile, myFixture.editor.document)
        Disposer.register(testRootDisposable, slice)
        return slice to model
    }

    private fun visible(slice: SliceEditor): String {
        val text = slice.editor.document.charsSequence
        val folds = slice.editor.foldingModel.allFoldRegions.filter { !it.isExpanded }
        return text.indices.filter { o -> folds.none { o >= it.startOffset && o < it.endOffset } }.map { text[it] }.joinToString("")
    }

    fun testClassSliceShowsNoMethodLines() {
        val (slice, model) = open()
        val cls = model.block("class:\\Foo")
        slice.show(cls.range, cls.excluded)
        assertEquals("class Foo\n{\n    private string \$name = 'x';\n}", visible(slice))
    }

    fun testCaretIsClampedToTheSlice() {
        val (slice, model) = open()
        val bar = model.block("method:bar")
        slice.show(bar.range, bar.excluded)
        slice.editor.caretModel.moveToOffset(0)
        assertEquals(bar.range.start, slice.editor.caretModel.offset)
    }

    fun testTypingAtTheEndOfTheSliceIsNotClampedBack() {
        val (slice, model) = open()
        val bar = model.block("method:bar")
        slice.show(bar.range, bar.excluded)
        WriteCommandAction.runWriteCommandAction(project) {
            slice.editor.caretModel.moveToOffset(bar.range.end)
            EditorModificationUtil.insertStringAtCaret(slice.editor, " // end")
        }
        assertEquals(bar.range.end + 7, slice.editor.caretModel.offset)
    }

    fun testCaretSkipsTheHoleWhereMethodsWere() {
        val (slice, model) = open()
        val cls = model.block("class:\\Foo")
        slice.show(cls.range, cls.excluded)
        val hole = slice.editor.foldingModel.allFoldRegions.single { it.startOffset > cls.range.start && it.endOffset < cls.range.end }
        slice.editor.caretModel.moveToOffset(hole.startOffset - 2)
        slice.editor.caretModel.moveToOffset(hole.startOffset)
        assertEquals(hole.endOffset, slice.editor.caretModel.offset)
        slice.editor.caretModel.moveToOffset(hole.startOffset)
        assertEquals(hole.startOffset - 1, slice.editor.caretModel.offset)
    }

    fun testShowAgainReplacesTheFolds() {
        val (slice, model) = open()
        val cls = model.block("class:\\Foo")
        slice.show(cls.range, cls.excluded)
        slice.show(cls.range, emptyList())
        assertTrue(visible(slice).contains("function bar"))
    }
}
