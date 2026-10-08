package dev.codelanes.canvas

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.codelanes.layout.Point
import dev.codelanes.settings.PinStore

class BlocksSessionTest : BasePlatformTestCase() {
    private val source = """
        <?php
        namespace App;

        class Foo
        {
            private string ${'$'}name = 'x';

            public function bar(): string
            {
                return ${'$'}this->footest(${'$'}this->name);
            }

            public function footest(string ${'$'}s): string
            {
                return ${'$'}s;
            }
        }

    """.trimIndent()

    private fun open(): BlocksSession {
        val psi = myFixture.configureByText("Foo.php", source)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        return BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
    }

    /** Replaces the first occurrence of [old] like a user edit would (keeps markers outside the edit valid). */
    private fun edit(old: String, new: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val at = document.text.indexOf(old)
            assertTrue("'$old' not found", at >= 0)
            document.replaceString(at, at + old.length, new)
        }
    }

    fun testOpensWithOneViewPerBlockAndEditorsForExpandedBlocks() {
        val session = open()
        assertEquals(listOf("header", "class:\\App\\Foo", "method:bar", "method:footest"), session.blockIds())
        assertTrue(session.hasSliceEditor("class:\\App\\Foo"))
        assertTrue(session.hasSliceEditor("method:bar"))
        assertFalse(session.hasSliceEditor("header"))
    }

    fun testNewMethodAppearsAfterRebuild() {
        val session = open()
        edit("        return ${'$'}s;\n    }\n", "        return ${'$'}s;\n    }\n\n    public function added(): void {}\n")
        session.rebuildNow()
        assertTrue(session.blockIds().contains("method:added"))
        assertTrue(session.hasSliceEditor("method:added"))
    }

    fun testSyntaxErrorKeepsLastGoodModel() {
        val session = open()
        val before = session.model
        edit("    public function footest", "    public function (\n    public function footest")
        session.rebuildNow()
        assertSame(before, session.model)
        assertEquals(RebuildPolicy.SYNTAX_NOTICE, session.canvas.notice)
    }

    fun testUnsupportedEditKeepsLastGoodModelWithNotice() {
        val session = open()
        val before = session.model
        edit("namespace App;", "namespace App;\necho 1;")
        session.rebuildNow()
        assertSame(before, session.model)
        assertEquals("Blocks paused: File contains code before the class", session.canvas.notice)
    }

    fun testDraggedBlockIsPinned() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        assertEquals(Point(900, 900), PinStore.getInstance(project).pins(myFixture.file.virtualFile.path)["method:bar"])
        assertEquals(java.awt.Point(940, 940), session.viewBounds("method:bar")!!.location)
    }

    fun testRenamingAMethodKeepsItsEditorAndPin() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        val editor = session.sliceEditor("method:bar")
        edit("function bar()", "function baz()")
        session.rebuildNow()
        val pins = PinStore.getInstance(project).pins(myFixture.file.virtualFile.path)
        assertFalse(pins.containsKey("method:bar"))
        assertEquals(Point(900, 900), pins["method:baz"])
        assertFalse(session.blockIds().contains("method:bar"))
        assertSame(editor, session.sliceEditor("method:baz"))
    }

    private fun visible(slice: dev.codelanes.editor.SliceEditor): String {
        val text = slice.editor.document.charsSequence
        val folds = slice.editor.foldingModel.allFoldRegions.filter { it.isValid && !it.isExpanded }
        return text.indices.filter { o -> folds.none { o >= it.startOffset && o < it.endOffset } }.map { text[it] }.joinToString("")
    }

    fun testZoomWhileCodeIsBrokenKeepsSlicesOnTheirOwnCode() {
        val session = open()
        edit("    public function footest(string ${'$'}s): string\n    {\n        return ${'$'}s;\n    }\n", "    public function (\n")
        session.rebuildNow()
        session.canvas.setZoom(1.2)
        assertTrue(visible(session.sliceEditor("method:bar")!!).startsWith("public function bar(): string"))
    }

    fun testCollapsingWhileCodeIsBrokenKeepsSlicesOnTheirOwnCode() {
        val session = open()
        edit("private string ${'$'}name = 'x';\n", "private string ${'$'}name = 'x';\n    private int ${'$'}extraField = 1234567890;\n    public function (\n")
        session.rebuildNow()
        session.collapseToggled("method:footest")
        assertTrue(visible(session.sliceEditor("method:bar")!!).startsWith("public function bar(): string"))
    }

    fun testEditingAnExpandedInterfaceBlockKeepsItCorrect() {
        val barro = myFixture.addFileToProject("Barro.php", "<?php\nnamespace App;\n\ninterface Barro\n{\n    public function bar(): string;\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("class Foo", "class Foo implements Barro"))
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        session.collapseToggled("interface:\\App\\Barro")
        val slice = session.sliceEditor("interface:\\App\\Barro")!!
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = slice.editor.document
            doc.insertString(doc.text.indexOf("interface Barro"), "/** Added by hand. */\n")
        }
        session.canvas.setZoom(1.1)
        assertTrue(visible(slice).startsWith("interface Barro"))
    }

    fun testEditInsideAMethodDoesNotRefoldOtherSlices() {
        val session = open()
        val classSlice = session.sliceEditor("class:\\App\\Foo")!!
        val folds = classSlice.editor.foldingModel.allFoldRegions.toList()
        edit("return ${'$'}s;", "return ${'$'}s . 'x';")
        session.rebuildNow()
        assertEquals(folds, classSlice.editor.foldingModel.allFoldRegions.toList())
    }

    fun testTallMethodScrollsInsideItsBlock() {
        val body = (1..80).joinToString("") { "        echo $it;\n" }
        val psi = myFixture.configureByText("Tall.php", "<?php\nclass Tall\n{\n    public function long(): void\n    {\n$body    }\n\n    public function short(): void\n    {\n    }\n}\n")
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        assertTrue(session.sliceEditor("method:long")!!.scrollable)
        assertFalse(session.sliceEditor("method:short")!!.scrollable)
    }

    fun testCollapseToggleReleasesAndRecreatesTheEditor() {
        val session = open()
        session.collapseToggled("method:bar")
        assertFalse(session.hasSliceEditor("method:bar"))
        session.collapseToggled("method:bar")
        assertTrue(session.hasSliceEditor("method:bar"))
    }

    fun testScrollingOverABlocksCodePansTheCanvas() {
        val session = open()
        val before = session.viewBounds("method:bar")!!.location
        val content = session.sliceEditor("method:bar")!!.editor.contentComponent
        content.dispatchEvent(
            java.awt.event.MouseWheelEvent(content, java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 5, 5, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1),
        )
        assertEquals(java.awt.Point(before.x, before.y - 40), session.viewBounds("method:bar")!!.location)
    }

    fun testResetLayoutDropsPinsAndRestoresAutoPositions() {
        val session = open()
        val auto = session.viewBounds("method:bar")!!.location
        session.blockMoved("method:bar", Point(900, 900))
        session.resetLayout()
        assertTrue(PinStore.getInstance(project).pins(myFixture.file.virtualFile.path).isEmpty())
        assertEquals(auto, session.viewBounds("method:bar")!!.location)
    }

    fun testBackgroundRebuildPicksUpANewMethod() {
        val session = open()
        edit("        return ${'$'}s;\n    }\n", "        return ${'$'}s;\n    }\n\n    public function added(): void {}\n")
        val deadline = System.currentTimeMillis() + 10_000
        while (!session.blockIds().contains("method:added") && System.currentTimeMillis() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            Thread.sleep(20)
        }
        assertTrue(session.blockIds().contains("method:added"))
    }

    fun testMethodTypedInTheFocusedClassBlockPopsOutWhenFocusLeaves() {
        val session = open()
        session.focusMovedTo("class:\\App\\Foo")
        edit("private string ${'$'}name = 'x';\n", "private string ${'$'}name = 'x';\n\n    public function added(): void {}\n")
        session.rebuildNow()
        assertFalse(session.blockIds().contains("method:added"))
        session.focusMovedTo(null)
        assertTrue(session.blockIds().contains("method:added"))
    }

    fun testTidyUpResetsTheLayout() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        session.tidyUp()
        assertTrue(PinStore.getInstance(project).pins(myFixture.file.virtualFile.path).isEmpty())
    }

    fun testScrollingOverABlocksGutterPansTheCanvasToo() {
        val session = open()
        val before = session.viewBounds("method:bar")!!.location
        val gutter = session.sliceEditor("method:bar")!!.editor.gutterComponentEx
        gutter.dispatchEvent(
            java.awt.event.MouseWheelEvent(gutter, java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 2, 5, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1),
        )
        assertEquals(java.awt.Point(before.x, before.y - 40), session.viewBounds("method:bar")!!.location)
    }

    fun testCaretInsideAnExpandedParentMethodShowsItsOverrideLine() {
        myFixture.addFileToProject("Base.php", "<?php\nnamespace App;\n\nabstract class Base\n{\n    protected int ${'$'}x = 0;\n\n    abstract public function bar(): string;\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("class Foo", "class Foo extends Base"))
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        val parentId = "parent:\\App\\Base"
        session.collapseToggled(parentId)
        val slice = session.sliceEditor(parentId)!!
        session.focusMovedTo(parentId)
        slice.editor.caretModel.moveToOffset(slice.editor.document.text.indexOf("x = 0"))
        assertTrue(session.canvas.visibleLinks().none { it.kind == dev.codelanes.model.LinkKind.OVERRIDES })
        slice.editor.caretModel.moveToOffset(slice.editor.document.text.indexOf("function bar"))
        assertEquals(listOf("method:bar"), session.canvas.visibleLinks().filter { it.kind == dev.codelanes.model.LinkKind.OVERRIDES }.map { it.from })
    }

    fun testTheMoreBlockNeverOpensAnEditor() {
        (1..11).forEach { n -> myFixture.addFileToProject("R$n.php", "<?php\nnamespace App;\nclass R$n implements Renderable { public function render(): string { return ''; } }\n") }
        val psi = myFixture.configureByText("Renderable.php", "<?php\nnamespace App;\n\ninterface Renderable\n{\n    public function render(): string;\n}\n")
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        assertTrue(session.blockIds().contains("more:implementers"))
        session.collapseToggled("more:implementers")
        assertFalse(session.hasSliceEditor("more:implementers"))
    }
}
