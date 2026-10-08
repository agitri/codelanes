package com.readcodelikeahuman.canvas

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.layout.Point
import com.readcodelikeahuman.settings.PinStore

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

    fun testRenamedBlockDropsItsPin() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        edit("function bar()", "function baz()")
        session.rebuildNow()
        assertFalse(PinStore.getInstance(project).pins(myFixture.file.virtualFile.path).containsKey("method:bar"))
        assertTrue(session.blockIds().contains("method:baz"))
        assertFalse(session.blockIds().contains("method:bar"))
    }

    fun testCollapseToggleReleasesAndRecreatesTheEditor() {
        val session = open()
        session.collapseToggled("method:bar")
        assertFalse(session.hasSliceEditor("method:bar"))
        session.collapseToggled("method:bar")
        assertTrue(session.hasSliceEditor("method:bar"))
    }
}
