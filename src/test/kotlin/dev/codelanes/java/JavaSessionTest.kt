package dev.codelanes.java

import com.intellij.openapi.util.Disposer
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.settings.PinStore

/** A Java file driven through the same session the PHP canvas uses. */
class JavaSessionTest : JavaBuilderTestCase() {
    private var session: BlocksSession? = null

    /** The light Java fixture checks for unreleased editors before testRootDisposable is disposed, so release the session first. */
    override fun tearDown() {
        try {
            session?.let { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun open(text: String, name: String = "OrderService.java"): BlocksSession {
        val psi = myFixture.configureByText(name, text)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        return BlocksSession(project, psi.virtualFile).also { session = it }
    }

    fun testAJavaFileOpensWithItsMethodBlocks() {
        val session = open("package com.acme;\npublic class OrderService {\n    public void place() {}\n}\n")
        assertTrue(session.hasSliceEditor("method:place"))
    }

    fun testPlusMethodAddsAJavaMethod() {
        val session = open("package com.acme;\npublic class OrderService {\n    public void place() {}\n}\n")
        session.addMethod()
        assertTrue(myFixture.editor.document.text.contains("    public void newMethod() {\n    }\n"))
        assertTrue(session.hasSliceEditor("method:newMethod"))
    }

    fun testPlusMethodInAnInterfaceAddsASignature() {
        val session = open("package com.acme;\npublic interface Repo {\n    void save();\n}\n", "Repo.java")
        session.addMethod()
        assertTrue(myFixture.editor.document.text.contains("    void newMethod();\n"))
    }

    fun testFollowChainReachesAnotherClass() {
        addClass("package com.acme; public class Mailer { public void send() {} }")
        val session = open("package com.acme;\npublic class OrderService {\n    private final Mailer m = new Mailer();\n    public void place() { m.send(); }\n}\n")
        session.followChain("method:place", 3)
        assertTrue(session.hasSliceEditor("callee:com.acme.Mailer::send"))
    }

    fun testALibraryParentWithoutSourceGetsNoEditorAndDoesNotFold() {
        val session = open("package com.acme;\npublic class MyTest extends junit.framework.TestCase {\n}\n", "MyTest.java")
        val id = "parent:junit.framework.TestCase"
        assertFalse(session.hasSliceEditor(id))
        session.collapseToggled(id)
        assertFalse(session.hasSliceEditor(id))
    }
}
