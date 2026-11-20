package dev.codelanes.kotlin

import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import org.jetbrains.kotlin.psi.KtFile

class KotlinBlockBuilderCallsTest : KotlinTestCase() {
    /** Builds on a pooled thread: K2 refuses DefinitionsScopedSearch on the EDT, where tests run. */
    private fun model(path: String, text: String, revealed: Set<String> = emptySet(), followed: Set<String> = emptySet()): BlockModel {
        val file = open(path, text) as KtFile
        return inBackground {
            when (val result = KotlinBlockBuilder.build(file, revealed, followed, emptySet())) {
                is BuildResult.Supported -> result.model
                is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
            }
        }
    }

    private fun BlockModel.ids(kind: BlockKind) = ofKind(kind).map { it.id }.toSet()

    private val service = """
        package acme

        class Service(private val repo: Repo) {
            fun place() {
                repo.save()
                audit()
                check()
            }
            fun check() {}
        }
    """

    private fun addRepoAndAudit() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save() {}\n    companion object { fun create() = Repo() }\n    fun deeper() { audit() }\n}")
        addFile("acme/Audit.kt", "package acme\n\nfun audit() { Repo().deeper() }")
    }

    fun testFollowedCallsBecomeCallees() {
        addRepoAndAudit()
        val model = model("acme/Service.kt", service, followed = setOf("method:Service.place"))
        assertTrue(Link(LinkKind.CALLS_INTO, "method:Service.place", "callee:acme.Repo::save") in model.links)
        assertTrue(Link(LinkKind.CALLS_INTO, "method:Service.place", "callee:acme.Audit::audit") in model.links)
        assertEquals("Repo::fun save()", model.block("callee:acme.Repo::save").title)
        assertTrue(model.block("callee:acme.Repo::save").filePath.endsWith("acme/Repo.kt"))
        // The same-file call stays a CALLS line, not a callee; callees are not followed unless asked.
        assertEquals(setOf("callee:acme.Repo::save", "callee:acme.Audit::audit"), model.ids(BlockKind.CALLEE))
        assertTrue(Link(LinkKind.CALLS, "method:Service.place", "method:Service.check") in model.links)
    }

    fun testNoCalleesWithoutPlusCalls() {
        addRepoAndAudit()
        assertTrue(model("acme/Service.kt", service).ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testFollowingACalleeGoesDeeper() {
        addRepoAndAudit()
        val model = model("acme/Service.kt", service, followed = setOf("method:Service.place", "callee:acme.Audit::audit"))
        assertTrue(Link(LinkKind.CALLS_INTO, "callee:acme.Audit::audit", "callee:acme.Repo::deeper") in model.links)
    }

    fun testCompanionAndOverloadCalleeIds() {
        addFile("acme/Repo.kt", "package acme\n\nclass Repo {\n    fun save(a: Int) {}\n    fun save(a: String) {}\n    companion object { fun create() = Repo() }\n}")
        val model = model("acme/Svc.kt", "package acme\n\nfun run(r: Repo) { Repo.create(); r.save(1) }", followed = setOf("method:run"))
        assertEquals(setOf("callee:acme.Repo::Companion.create", "callee:acme.Repo::save(Int)"), model.ids(BlockKind.CALLEE))
    }

    fun testCallToJavaBecomesJavaCallee() {
        val java = addFile("acme/JavaRepo.java", "package acme;\npublic class JavaRepo {\n    public void find() {}\n}")
        val model = model("acme/Svc.kt", "package acme\n\nfun run(r: JavaRepo) { r.find() }", followed = setOf("method:run"))
        val callee = model.block("callee:acme.JavaRepo::find")
        assertTrue(Link(LinkKind.CALLS_INTO, "method:run", callee.id) in model.links)
        assertEquals(java.text.indexOf("public void"), callee.range.start)
        assertTrue(callee.filePath.endsWith("acme/JavaRepo.java"))
    }

    fun testInterfaceCallShowsImplementations() {
        addFile("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store { override fun put() {} }")
        addFile("acme/MemStore.kt", "package acme\n\nclass MemStore : Store { override fun put() {} }")
        val model = model("acme/Svc.kt", "package acme\n\nfun run(store: Store) { store.put() }", followed = setOf("method:run"))
        assertEquals(
            setOf("callee:acme.Store::put", "callee:acme.DbStore::put", "callee:acme.MemStore::put"),
            model.ids(BlockKind.CALLEE),
        )
        assertTrue(Link(LinkKind.IMPLEMENTED_BY, "callee:acme.Store::put", "callee:acme.DbStore::put") in model.links)
        assertTrue(Link(LinkKind.IMPLEMENTED_BY, "callee:acme.Store::put", "callee:acme.MemStore::put") in model.links)
    }

    fun testImplementationsStopAtFive() {
        addFile("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        (1..7).forEach { addFile("acme/Impl$it.kt", "package acme\n\nclass Impl$it : Store { override fun put() {} }") }
        val model = model("acme/Svc.kt", "package acme\n\nfun run(store: Store) { store.put() }", followed = setOf("method:run"))
        assertEquals(5, model.links.count { it.kind == LinkKind.IMPLEMENTED_BY })
    }

    fun testLibraryCallsAreNotCallees() {
        // No stdlib in the fixture: the library call goes into junit's compiled Assert.
        val model = model("acme/Svc.kt", "package acme\n\nfun run() { junit.framework.Assert.assertTrue(true) }", followed = setOf("method:run"))
        assertTrue(model.ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testImplementersOfAnInterface() {
        addFile("acme/DbStore.kt", "package acme\n\nclass DbStore : Store {\n    override fun put() {}\n    fun other() {}\n}")
        addFile("acme/MemStore.kt", "package acme\n\nclass MemStore : Store { override fun put() {} }")
        val model = model("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        assertEquals(setOf("implementer:acme.DbStore", "implementer:acme.MemStore"), model.ids(BlockKind.IMPLEMENTER))
        assertTrue(Link(LinkKind.IMPLEMENTED_BY, "class:acme.Store", "implementer:acme.DbStore") in model.links)
        assertEquals(listOf("fun put()"), model.block("implementer:acme.DbStore").summary)
        assertTrue(model.block("implementer:acme.DbStore").collapsed)
        assertEquals(BlockKind.IMPLEMENTATION, model.block("implementation:acme.DbStore::put").kind)
        assertTrue(Link(LinkKind.OWNS, "implementer:acme.DbStore", "implementation:acme.DbStore::put") in model.links)
        assertTrue(Link(LinkKind.OVERRIDES, "implementation:acme.DbStore::put", "method:Store.put", model.block("method:Store.put").range) in model.links)
    }

    fun testImplementersOfAbstractAndSealedClasses() {
        addFile("acme/Sub.kt", "package acme\n\nclass Sub : Shape()\nclass Circle : Sealed()")
        val abstract = model("acme/Shape.kt", "package acme\n\nabstract class Shape")
        assertEquals(setOf("implementer:acme.Sub"), abstract.ids(BlockKind.IMPLEMENTER))
        val sealed = model("acme/Sealed.kt", "package acme\n\nsealed class Sealed")
        assertEquals(setOf("implementer:acme.Circle"), sealed.ids(BlockKind.IMPLEMENTER))
    }

    fun testAPlainClassShowsNoImplementers() {
        addFile("acme/Sub.kt", "package acme\n\nopen class Sub : Plain()")
        assertTrue(model("acme/Plain.kt", "package acme\n\nopen class Plain").ofKind(BlockKind.IMPLEMENTER).isEmpty())
    }

    fun testImplementersInTheSameFileLinkClassToClass() {
        val model = model("acme/Store.kt", "package acme\n\ninterface Store { fun put() }\nclass Db : Store { override fun put() {} }")
        assertTrue(model.ofKind(BlockKind.IMPLEMENTER).isEmpty())
        assertTrue(Link(LinkKind.IMPLEMENTED_BY, "class:acme.Store", "class:acme.Db") in model.links)
    }

    fun testImplementersStopAtTheCap() {
        (1..12).forEach { addFile("acme/Impl$it.kt", "package acme\n\nclass Impl$it : Store { override fun put() {} }") }
        val model = model("acme/Store.kt", "package acme\n\ninterface Store { fun put() }")
        assertEquals(10, model.ofKind(BlockKind.IMPLEMENTER).size)
        assertEquals("and 2 more", model.block(KotlinBlockBuilder.MORE_ID).title)
        assertTrue(Link(LinkKind.IMPLEMENTED_BY, "class:acme.Store", KotlinBlockBuilder.MORE_ID) in model.links)
    }

    fun testPlusParentsDigsDeeper() {
        addFile("acme/Root.kt", "package acme\n\nopen class Root")
        addFile("acme/Base.kt", "package acme\n\nopen class Base : Root()")
        val text = "package acme\n\nclass A : Base()"
        assertTrue(model("acme/A.kt", text).blocks.none { it.id == "parent:acme.Root" })
        val model = model("acme/A2.kt", text.replace("class A", "class A2"), revealed = setOf("parent:acme.Base"))
        assertTrue(Link(LinkKind.EXTENDS, "parent:acme.Root", "parent:acme.Base") in model.links)
        assertEquals(BlockKind.PARENT, model.block("parent:acme.Root").kind)
    }

    fun testChainCap() {
        (1..12).forEach { i ->
            addFile("acme/C$i.kt", "package acme\n\nfun go$i() { " + (1..12).joinToString("; ") { "x${i}_$it()" } + " }")
            (1..12).forEach { j -> addFile("acme/D${i}_$j.kt", "package acme\n\nfun x${i}_$j() {}") }
        }
        val calls = (1..12).joinToString("\n") { "    go$it()" }
        val followed = (1..12).map { "callee:acme.C$it::go$it" }.toSet() + "method:run"
        val model = model("acme/Big.kt", "package acme\n\nfun run() {\n$calls\n}", followed = followed)
        assertTrue(model.blocks.count { it.kind == BlockKind.CALLEE } <= KotlinBlockBuilder.MAX_CHAIN_BLOCKS)
        assertEquals(KotlinBlockBuilder.MORE_CHAIN_ID, model.block(KotlinBlockBuilder.MORE_CHAIN_ID).id)
        assertTrue(Link(LinkKind.CALLS_INTO, "method:run", KotlinBlockBuilder.MORE_CHAIN_ID) in model.links)
    }

    fun testMoreThanTenCalleesGetAMoreBlock() {
        (1..12).forEach { addFile("acme/F$it.kt", "package acme\n\nfun f$it() {}") }
        val calls = (1..12).joinToString("\n") { "    f$it()" }
        val model = model("acme/Big.kt", "package acme\n\nfun run() {\n$calls\n}", followed = setOf("method:run"))
        assertEquals(10, model.ofKind(BlockKind.CALLEE).size)
        assertEquals("and 2 more calls", model.block("more:calls:method:run").title)
    }
}
