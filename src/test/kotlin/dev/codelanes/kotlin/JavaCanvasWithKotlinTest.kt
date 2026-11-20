package dev.codelanes.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.java.JavaBlockBuilder
import dev.codelanes.model.Block
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.LinkKind
import dev.codelanes.settings.PinStore

/** A Java canvas in a mixed project: Kotlin parents, dependencies and callees show as Kotlin, with Kotlin ranges and ids. */
class JavaCanvasWithKotlinTest : KotlinTestCase() {
    private var session: BlocksSession? = null

    override fun tearDown() {
        try {
            session?.let { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun build(file: PsiFile, followed: Set<String> = emptySet()): BlockModel =
        inBackground { (JavaBlockBuilder.build(file, emptySet(), followed) as BuildResult.Supported).model }

    private fun textOf(file: PsiFile, block: Block): String = file.text.substring(block.range.start, block.range.end)

    private fun block(model: BlockModel, id: String): Block =
        model.blocks.firstOrNull { it.id == id } ?: error("no $id in ${model.blocks.map { it.id }}")

    fun testAKotlinParentShowsAsKotlin() {
        val base = addFile("acme/Base.kt", "package acme\n\nopen class Base {\n    open fun hello() {}\n}")
        val file = addFile("acme/Service.java", "package acme;\npublic class Service extends Base {\n    @Override public void hello() {}\n}")
        val model = build(file)
        val parent = block(model, "parent:acme.Base")
        assertEquals(base.virtualFile.path, parent.filePath)
        assertTrue(textOf(base, parent), textOf(base, parent).startsWith("open class Base"))
        assertEquals("class Base", parent.title)
        assertEquals(listOf("fun hello()"), parent.summary)
        assertFalse(parent.sourceless)
        val override = model.links.single { it.kind == LinkKind.OVERRIDES }
        assertEquals("parent:acme.Base", override.to)
        assertTrue(base.text.substring(override.targetRange!!.start, override.targetRange!!.end).startsWith("open fun hello"))
    }

    fun testAnInjectedKotlinClassShowsAsKotlin() {
        val repo = addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n}")
        val file = addFile("acme/Service.java", "package acme;\npublic class Service {\n    public Service(Repo repo) {}\n}")
        val dependency = block(build(file), "dependency:acme.Repo")
        assertEquals(repo.virtualFile.path, dependency.filePath)
        assertTrue(textOf(repo, dependency).startsWith("class Repo"))
        assertEquals(listOf("fun save()"), dependency.summary)
    }

    fun testFollowingACallIntoAKotlinMember() {
        val repo = addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n    fun save(id: Int) {}\n    fun load() {}\n}")
        val file = addFile("acme/Service.java", "package acme;\npublic class Service {\n    private Repo repo;\n    void run() { repo.save(); repo.save(1); repo.load(); }\n}")
        val model = build(file, setOf("method:run"))
        val save = block(model, "callee:acme.Repo::save()")
        assertEquals(repo.virtualFile.path, save.filePath)
        assertTrue(textOf(repo, save).startsWith("fun save()"))
        assertEquals("Repo::fun save()", save.title)
        assertTrue(textOf(repo, block(model, "callee:acme.Repo::save(Int)")).startsWith("fun save(id: Int)"))
        assertTrue(textOf(repo, block(model, "callee:acme.Repo::load")).startsWith("fun load()"))
        assertTrue(model.links.any { it.kind == LinkKind.CALLS_INTO && it.from == "method:run" && it.to == "callee:acme.Repo::load" })
    }

    fun testFollowingACallIntoATopLevelKotlinFunction() {
        val screen = addFile("acme/NoteScreen.kt", "package acme\n\nfun format() {}")
        val file = addFile("acme/Service.java", "package acme;\npublic class Service {\n    void run() { NoteScreenKt.format(); }\n}")
        val format = block(build(file, setOf("method:run")), "callee:acme.NoteScreen::format")
        assertEquals(screen.virtualFile.path, format.filePath)
        assertTrue(textOf(screen, format).startsWith("fun format()"))
    }

    fun testAKotlinImplementerOfAJavaInterfaceShowsAsKotlin() {
        val db = addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store {\n    override fun put() {}\n}")
        val file = addFile("acme/Store.java", "package acme;\npublic interface Store { void put(); }")
        val model = build(file)
        val implementer = block(model, "implementer:acme.DbStore")
        assertEquals(db.virtualFile.path, implementer.filePath)
        assertTrue(textOf(db, implementer).startsWith("class DbStore"))
        val implementation = model.blocks.single { it.id.startsWith("implementation:") }
        assertEquals(db.virtualFile.path, implementation.filePath)
        assertTrue(implementation.id + ": " + textOf(db, implementation), textOf(db, implementation).startsWith("override fun put"))
    }

    private fun openSession(path: String, text: String): BlocksSession {
        val psi = myFixture.addFileToProject(path, text)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        assertTrue("the test must run on the EDT", ApplicationManager.getApplication().isDispatchThread)
        return BlocksSession(project, psi.virtualFile).also { session = it }
    }

    /**
     * Java canvases still build synchronously on the EDT (no buildsInBackground), where K2 refuses analysis. Resolving
     * into Kotlin light classes, overrides, injection, facade calls and implementations of a Kotlin interface must work there.
     */
    fun testFollowingCallsIntoKotlinThroughTheSessionOnTheEdt() {
        addFile("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        addFile("acme/Base.kt", "package acme\n\nopen class Base { open fun hello() {} }")
        addFile("acme/Repo.kt", "package acme\n\nclass Repo { fun save() {} }")
        addFile("acme/NoteScreen.kt", "package acme\n\nfun format() {}")
        val session = openSession("acme/Service.java", "package acme;\npublic class Service extends Base {\n    public Service(Repo repo) {}\n" +
            "    @Override public void hello() {}\n    void run(Store store, Repo repo) { store.put(); repo.save(); NoteScreenKt.format(); }\n}\n")
        assertTrue(session.blockIds().containsAll(listOf("parent:acme.Base", "dependency:acme.Repo")))
        session.followChain("method:run", 2)
        val ids = session.blockIds()
        val expected = listOf("callee:acme.Store::put", "callee:acme.DbStore::put", "callee:acme.Repo::save", "callee:acme.NoteScreen::format")
        assertTrue(ids.toString(), ids.containsAll(expected))
    }

    fun testAJavaInterfaceWithAKotlinImplementerOpensOnTheEdt() {
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        val session = openSession("acme/Store.java", "package acme;\npublic interface Store { void put(); }\n")
        assertTrue(session.blockIds().toString(), session.blockIds().contains("implementer:acme.DbStore"))
    }
}
