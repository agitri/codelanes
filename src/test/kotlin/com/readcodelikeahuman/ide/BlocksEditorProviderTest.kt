package com.readcodelikeahuman.ide

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.canvas.BlocksCanvas
import com.readcodelikeahuman.settings.BlocksSettings

class BlocksEditorProviderTest : BasePlatformTestCase() {
    private val provider = BlocksEditorProvider()

    fun testAcceptsSupportedPhpFile() {
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo {}\n").virtualFile
        assertTrue(provider.accept(project, file))
    }

    fun testRejectsUnsupportedPhpFile() {
        val file = myFixture.configureByText("Two.php", "<?php\nclass A {}\nclass B {}\n").virtualFile
        assertFalse(provider.accept(project, file))
    }

    fun testRejectsNonPhpFile() {
        val file = myFixture.configureByText("notes.txt", "hello").virtualFile
        assertFalse(provider.accept(project, file))
    }

    fun testPolicyFollowsTheSetting() {
        val state = BlocksSettings.instance.state
        val original = state.openAsBlocksByDefault
        try {
            state.openAsBlocksByDefault = true
            assertEquals(FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR, provider.policy)
            state.openAsBlocksByDefault = false
            assertEquals(FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR, provider.policy)
        } finally {
            state.openAsBlocksByDefault = original
        }
    }

    fun testCreatesBlocksEditorWithCanvas() {
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo {}\n").virtualFile
        val editor = provider.createEditor(project, file)
        try {
            assertEquals("Blocks", editor.name)
            assertInstanceOf(editor.component, BlocksCanvas::class.java)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testAcceptWorksWhileIndexingWithoutResolvingReferences() {
        myFixture.addFileToProject("Barro.php", "<?php\ninterface Barro {}\n")
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo implements Barro {\n    public function a() { \$this->b(); }\n    public function b() {}\n}\n").virtualFile
        assertTrue((provider as Any) is com.intellij.openapi.project.DumbAware)
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(provider.accept(project, file))
        }
    }
}
