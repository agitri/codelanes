package dev.codelanes.ide

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BlocksFileEditorTest : BasePlatformTestCase() {
    fun testNavigationInsideTheFileStaysOnTheCanvas() {
        myFixture.addFileToProject("Other.php", "<?php\nclass Other {}\n")
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo\n{\n    public function bar(): void {}\n}\n").virtualFile
        val other = myFixture.findFileInTempDir("Other.php")
        val editor = BlocksFileEditor(project, file)
        try {
            val offset = myFixture.editor.document.text.indexOf("bar(")
            assertTrue(editor.canNavigateTo(OpenFileDescriptor(project, file, offset)))
            assertFalse(editor.canNavigateTo(OpenFileDescriptor(project, other, 0)))
            assertFalse(editor.canNavigateTo(OpenFileDescriptor(project, file, myFixture.editor.document.textLength)))
            editor.navigateTo(OpenFileDescriptor(project, file, offset))
            assertEquals("method:bar", editor.session.canvas.focusedId)
        } finally {
            Disposer.dispose(editor)
        }
    }
}
