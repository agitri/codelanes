package dev.codelanes.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.flow.FlowResolver
import dev.codelanes.lang.FlowTarget
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BuildResult
import dev.codelanes.settings.PinStore
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * A Kotlin file driven through the session. The session's synchronous rebuild (on open) runs on the calling
 * thread, the EDT here and in the IDE, where K2 refuses DefinitionsScopedSearch (ProhibitedAnalysisException).
 */
class KotlinSessionTest : KotlinTestCase() {
    private var session: BlocksSession? = null
    private var opened: PsiFile? = null

    override fun tearDown() {
        try {
            session?.let { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun openSession(path: String, text: String, follow: String? = null): BlocksSession {
        val psi = myFixture.addFileToProject(path, text) // verbatim: the trailing newline matters here
        opened = psi
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        assertTrue("the test must run on the EDT", ApplicationManager.getApplication().isDispatchThread)
        return BlocksSession(project, psi.virtualFile).also { session = it }
    }

    fun testOpeningAnInterfaceCanvasOnTheEdtShowsImplementers() {
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        val session = openSession("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        assertTrue(session.blockIds().contains("implementer:acme.DbStore"))
    }

    fun testFollowingAnInterfaceCallOnTheEdtShowsImplementations() {
        addFile("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        val session = openSession("acme/Svc.kt", "package acme\n\nfun run(store: Store) { store.put() }")
        session.followChain("method:run", 2)
        assertTrue(session.blockIds().contains("callee:acme.DbStore::put"))
    }

    private fun <T> cancellingBuilds(block: () -> T): T {
        BlocksSession.cancelModalBuildsForTests = true
        try {
            return block()
        } finally {
            BlocksSession.cancelModalBuildsForTests = false
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(what, condition, 20)
    }

    /** Cancelling "Building blocks" while the file opens: the canvas still opens, empty with a notice, and builds later. */
    fun testCancellingTheBuildOnOpenStillOpensTheCanvas() {
        val session = cancellingBuilds { openSession("acme/Svc.kt", "package acme\n\nfun run() {}") }
        assertNull(session.model)
        assertEquals(BlocksSession.OPEN_CANCELLED_NOTICE, session.canvas.notice)
        waitFor("the background build after a cancelled open") { "method:run" in session.blockIds() }
    }

    /** Cancelling "Building blocks" in an action leaves the canvas, and what it follows and shows, as it was. */
    fun testCancellingTheBuildInAnActionPutsTheCanvasBack() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n}")
        val session = openSession("acme/Svc.kt", "package acme\n\nfun run(r: Repo) { r.save() }")
        val before = session.blockIds()
        cancellingBuilds {
            session.addToCanvas("acme.Repo", "save")
            session.followChain("method:run", 2)
        }
        assertEquals(before, session.blockIds())
        assertTrue(session.snapshot().added.isEmpty())
        assertTrue(session.snapshot().followedCalls.isEmpty())
        assertEquals(BlocksSession.CANCELLED_NOTICE, session.canvas.notice)
    }

    /** + calls on a Kotlin canvas needs no model right away: it builds in the background, without a modal progress. */
    fun testPlusCallsBuildsInTheBackground() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n}")
        val session = openSession("acme/Svc.kt", "package acme\n\nfun run(r: Repo) { r.save() }")
        cancellingBuilds { session.toggleCalls("method:run") } // a modal build would be cancelled here
        assertEquals(listOf("method:run"), session.snapshot().followedCalls)
        waitFor("the background build after + calls") { "callee:acme.Repo::save" in session.blockIds() }
    }

    private fun text(): String = FileDocumentManager.getInstance().getDocument(opened!!.virtualFile)!!.text

    fun testAddMethodToModule() {
        val session = openSession("acme/Svc.kt", "fun a() {}\n")
        session.addMethod("module:Svc")
        assertEquals("fun a() {}\n\nfun newFunction() {\n}\n", text())
        assertTrue(session.blockIds().contains("method:newFunction"))
        assertEquals("newFunction", session.sliceEditor("method:newFunction")!!.editor.selectionModel.selectedText)
        session.addMethod("module:Svc")
        assertTrue(session.blockIds().contains("method:newFunction2"))
        assertEquals("fun a() {}\n\nfun newFunction() {\n}\n\nfun newFunction2() {\n}\n", text())
    }

    fun testAddMethodToClass() {
        val session = openSession("acme/C.kt", "class C {\n    fun a() {}\n}\n")
        session.addMethod("class:C")
        assertEquals("class C {\n    fun a() {}\n\n    fun newMethod() {\n    }\n}\n", text())
        assertTrue(session.blockIds().contains("method:C.newMethod"))
        assertEquals("newMethod", session.sliceEditor("method:C.newMethod")!!.editor.selectionModel.selectedText)
        session.addMethod("class:C")
        assertTrue(session.blockIds().contains("method:C.newMethod2"))
    }

    fun testAddMethodToBodylessClass() {
        val session = openSession("acme/C.kt", "class C(val x: Int)\n")
        session.addMethod("class:C")
        assertEquals("class C(val x: Int) {\n    fun newMethod() {\n    }\n}\n", text())
        assertTrue(session.blockIds().contains("method:C.newMethod"))
    }

    fun testAddMethodToAnEmptyBody() {
        val session = openSession("acme/C.kt", "class C {\n}\n")
        session.addMethod("class:C")
        assertEquals("class C {\n    fun newMethod() {\n    }\n}\n", text())
    }

    fun testAddMethodToObject() {
        val session = openSession("acme/O.kt", "object O {\n    fun a() {}\n}\n")
        session.addMethod("class:O")
        assertEquals("object O {\n    fun a() {}\n\n    fun newMethod() {\n    }\n}\n", text())
        assertTrue(session.blockIds().contains("method:O.newMethod"))
    }

    /** Members of an enum need a `;` after its entries: "+ method" adds one when there is none. */
    fun testAddMethodToAnEnumWithEntries() {
        val session = openSession("acme/E.kt", "enum class E {\n    A, B // the last\n}\n")
        session.addMethod("class:E")
        assertEquals("enum class E {\n    A, B; // the last\n\n    fun newMethod() {\n    }\n}\n", text())
        assertTrue(session.blockIds().contains("method:E.newMethod"))
        assertEquals("newMethod", session.sliceEditor("method:E.newMethod")!!.editor.selectionModel.selectedText)
        session.addMethod("class:E")
        assertEquals("enum class E {\n    A, B; // the last\n\n    fun newMethod() {\n    }\n\n    fun newMethod2() {\n    }\n}\n", text())
    }

    fun testAddMethodToAOneLineEnum() {
        val session = openSession("acme/E.kt", "enum class E { A(1), B(2) { override fun toString() = \"b;\" } }\n")
        session.addMethod("class:E")
        assertEquals("enum class E { A(1), B(2) { override fun toString() = \"b;\" }; \n    fun newMethod() {\n    }\n}\n", text())
        assertTrue(session.blockIds().contains("method:E.newMethod"))
    }

    fun testAddMethodToInterface() {
        val session = openSession("acme/I.kt", "interface I {\n    fun a()\n}\n")
        session.addMethod("class:I")
        assertEquals("interface I {\n    fun a()\n\n    fun newMethod()\n}\n", text())
        assertTrue(session.blockIds().contains("method:I.newMethod"))
    }

    fun testTheClassButtonAddsToThatClass() {
        val session = openSession("acme/Two.kt", "class A\n\nclass B {\n    fun b() {}\n}\n")
        session.view("class:B")!!.clickAction("+ method")
        assertTrue(session.blockIds().contains("method:B.newMethod"))
    }

    fun testDeleteRemovesExactlyItsLines() {
        val session = openSession("acme/C.kt", "class C {\n    fun a() {}\n\n    fun b() {}\n}\n\nfun top() {}\n")
        session.deleteMethod("method:C.a") { true }
        assertEquals("class C {\n\n    fun b() {}\n}\n\nfun top() {}\n", text())
        assertFalse(session.blockIds().contains("method:C.a"))
        assertTrue(session.blockIds().contains("method:C.b"))
        session.deleteMethod("method:top") { true }
        assertEquals("class C {\n\n    fun b() {}\n}\n", text())
    }

    fun testFlowIdsViaHook() {
        val repo = addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n    companion object { fun create() {} }\n}") as KtFile
        val audit = addFile("acme/Audit.kt", "package acme\n\nfun audit() {}") as KtFile
        val support = KotlinLanguageSupport()
        val cls = repo.declarations.filterIsInstance<KtClassOrObject>().single()
        val save = cls.declarations.filterIsInstance<KtNamedFunction>().single()
        val auditFn = audit.declarations.filterIsInstance<KtNamedFunction>().single()
        val classTarget = FlowTarget("acme.Repo", "acme.Repo", null, false, repo, cls, null, support)
        val saveTarget = FlowTarget("acme.Repo", "acme.Repo", "Repo.save", false, repo, cls, save, support)
        val auditTarget = FlowTarget("acme.Audit", "acme.Audit", "audit", false, audit, audit, auditFn, support)
        val added = linkedSetOf<String>()
        assertEquals("class:acme.Repo", FlowResolver.idOf(classTarget, "acme.Repo", added))
        assertEquals("method:Repo.save", FlowResolver.idOf(saveTarget, "acme.Repo", added))
        assertTrue(added.isEmpty())
        assertEquals("callee:acme.Repo::class", FlowResolver.idOf(classTarget, "acme.Svc", added))
        assertEquals("callee:acme.Repo::save", FlowResolver.idOf(saveTarget, "acme.Svc", added))
        assertEquals("callee:acme.Audit::audit", FlowResolver.idOf(auditTarget, "acme.Svc", added))
        assertEquals("method:audit", FlowResolver.idOf(auditTarget, "acme.Audit", added))
        assertEquals(setOf("acme.Repo::class", "acme.Repo::save", "acme.Audit::audit"), added)
    }

    fun testAddMethodListsProjectFunctions() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save(note: Note) {}\n    companion object { fun create() {} }\n}")
        addFile("acme/Audit.kt", "package acme\n\nfun audit() {}\nfun String.slug(): String = this")
        val refs = inBackground { KotlinMethodFinder.find(project) }
        val byKey = refs.associateBy { it.classFqn + "::" + it.method }
        assertEquals(setOf("acme.Repo::save", "acme.Repo::Companion.create", "acme.Audit::audit", "acme.Audit::slug"), byKey.keys)
        assertEquals("Repo::fun save(note: Note)  acme", byKey.getValue("acme.Repo::save").label)
        assertEquals("Audit::fun audit()  acme", byKey.getValue("acme.Audit::audit").label)
        assertEquals(refs.sortedBy { it.label }, refs)
    }

    fun testAddMethodFromAnotherFileShowsTheCallee() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n    companion object { fun create() {} }\n}")
        addFile("acme/Audit.kt", "package acme\n\nfun audit() {}")
        val session = openSession("acme/Svc.kt", "package acme\n\nfun run() {}")
        session.addToCanvas("acme.Repo", "save")
        assertTrue(session.blockIds().contains("callee:acme.Repo::save"))
        session.addToCanvas("acme.Repo", "Companion.create")
        assertTrue(session.blockIds().contains("callee:acme.Repo::Companion.create"))
        session.addToCanvas("acme.Audit", "audit")
        assertTrue(session.blockIds().contains("callee:acme.Audit::audit"))
        session.addToCanvas("acme.Repo", "save")
        assertEquals(1, session.blockIds().count { it == "callee:acme.Repo::save" })
    }

    fun testAddMethodFromAJavaFileShowsTheCallee() {
        addFile("acme/JRepo.java", "package acme;\npublic class JRepo { public void save() {} }")
        val session = openSession("acme/Svc.kt", "package acme\n\nfun run() {}")
        session.addToCanvas("acme.JRepo", "save")
        assertTrue(session.blockIds().contains("callee:acme.JRepo::save"))
    }

    fun testAnAddedClassKeyShowsACalleeBlock() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n}")
        val file = open("acme/Svc.kt", "package acme\n\nfun run() {}") as KtFile
        val model = inBackground { (KotlinBlockBuilder.build(file, emptySet(), emptySet(), setOf("acme.Repo::class")) as BuildResult.Supported).model }
        assertTrue(model.ofKind(BlockKind.CALLEE).map { it.id }.contains("callee:acme.Repo::class"))
    }

    fun testAddingAFunctionOfTheOpenFileFocusesItsBlockInsteadOfDuplicatingIt() {
        val session = openSession("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n    companion object { fun create() {} }\n}\n\nfun audit() {}")
        session.addToCanvas("acme.Repo", "save")
        assertFalse(session.blockIds().any { it.startsWith("callee:") })
        assertEquals("method:Repo.save", session.canvas.focusedId)
        session.addToCanvas("acme.Repo", "Companion.create")
        assertEquals("method:Repo.Companion.create", session.canvas.focusedId)
        session.addToCanvas("acme.Repo", "audit")
        assertFalse(session.blockIds().any { it.startsWith("callee:") })
        assertEquals("method:audit", session.canvas.focusedId)
    }

    fun testAnAddedKeyIntoTheOpenFileMakesNoCalleeBlock() {
        val file = open("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n}\n\nfun audit() {}") as KtFile
        val model = inBackground { (KotlinBlockBuilder.build(file, emptySet(), emptySet(), setOf("acme.Repo::save", "acme.Repo::Repo.save", "acme.Repo::audit", "acme.Repo::class")) as BuildResult.Supported).model }
        assertTrue(model.ofKind(BlockKind.CALLEE).isEmpty())
    }
}
