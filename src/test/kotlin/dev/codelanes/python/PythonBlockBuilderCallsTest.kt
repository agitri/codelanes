package dev.codelanes.python

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.jetbrains.python.psi.PyFile
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import java.nio.file.Files

class PythonBlockBuilderCallsTest : PythonTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LIBRARY_DESCRIPTOR

    override fun setUp() {
        super.setUp()
        addModule("shop/__init__.py", "")
        // No Python SDK: `abc` lives in the library root (as the standard library would), typing in the project.
        // As in typeshed, Generic and Protocol aren't classes named typing.Generic / typing.Protocol.
        addModule("typing.py", """
            from _forms import Generic
            class _SpecialForm: pass
            Protocol = _SpecialForm()
            TypeVar = object()
        """)
        addModule("shop/repo.py", """
            from abc import ABC, abstractmethod

            class Repo(ABC):
                @abstractmethod
                def save(self, o): pass

                def count(self): return 0

            class SqlRepo(Repo):
                def save(self, o):
                    pass
        """)
        addModule("shop/smtp.py", """
            def send_mail(o):
                pass
        """)
        addModule("shop/mail.py", """
            from shop.smtp import send_mail

            def notify(o):
                send_mail(o)
        """)
    }

    private val service = """
        from extlib.views import helper
        from shop.repo import Repo
        from shop.mail import notify

        class Service:
            def __init__(self, repo: Repo):
                self.repo = repo

            def place(self, o):
                self.repo.save(o)
                notify(o)
                helper()
                self.check(o)

            def check(self, o):
                pass
    """

    private fun model(text: String, path: String = "shop/service.py", revealed: Set<String> = emptySet(),
                      followed: Set<String> = emptySet(), added: Set<String> = emptySet()): BlockModel {
        val file = open(path, text) as PyFile
        return when (val result = PythonBlockBuilder.build(file, revealed, followed, added)) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }
    }

    /** Opens a module added in [setUp] or by the test. */
    private fun existing(path: String): BlockModel {
        val file = myFixture.configureFromTempProjectFile(path) as PyFile
        return (PythonBlockBuilder.build(file, emptySet(), emptySet(), emptySet()) as BuildResult.Supported).model
    }

    private fun BlockModel.ids(kind: BlockKind) = ofKind(kind).map { it.id }.toSet()

    fun testPlusCallsShowsCalleesInOtherModules() {
        val model = model(service, followed = setOf("method:Service.place"))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:Service.place", "callee:shop.repo::Repo.save")))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:Service.place", "callee:shop.mail::notify")))
        val save = model.block("callee:shop.repo::Repo.save")
        assertEquals("Repo::def save(self, o)", save.title)
        assertFalse(save.collapsed)
        assertTrue(save.filePath.endsWith("shop/repo.py"))
        val text = String(myFixture.findFileInTempDir("shop/repo.py").contentsToByteArray())
        assertTrue(text.substring(save.range.start, save.range.end).startsWith("@abstractmethod"))
        assertEquals("shop.mail::def notify(o)", model.block("callee:shop.mail::notify").title)
        // notify isn't followed: its own call to send_mail makes no block.
        assertFalse(model.blocks.any { it.id == "callee:shop.smtp::send_mail" })
    }

    fun testCallsInsideTheModuleAndIntoLibrariesMakeNoCallee() {
        val model = model(service, followed = setOf("method:Service.place"))
        assertTrue(model.ofKind(BlockKind.CALLEE).none { it.id.contains("helper") || it.id.contains("Service") })
        assertTrue(model.links.contains(Link(LinkKind.CALLS, "method:Service.place", "method:Service.check")))
    }

    fun testNoCalleesWithoutPlusCalls() {
        assertTrue(model(service).ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testFollowingACalleeGoesDeeper() {
        val model = model(service, followed = setOf("method:Service.place", "callee:shop.mail::notify"))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "callee:shop.mail::notify", "callee:shop.smtp::send_mail")))
    }

    fun testAnAddedFunctionIsShownWithoutBeingFollowed() {
        val model = model(service, added = setOf("shop.mail::notify"))
        assertEquals(setOf("callee:shop.mail::notify"), model.ids(BlockKind.CALLEE))
        assertTrue(model.links.none { it.kind == LinkKind.CALLS_INTO })
        val again = PythonBlockBuilder.build(myFixture.file as PyFile, emptySet(), setOf("callee:shop.mail::notify"), setOf("shop.mail::notify"))
        val followed = (again as BuildResult.Supported).model
        assertTrue(followed.links.contains(Link(LinkKind.CALLS_INTO, "callee:shop.mail::notify", "callee:shop.smtp::send_mail")))
    }

    fun testAnAddedMethodAndClass() {
        val model = model(service, added = setOf("shop.repo::SqlRepo.save", "shop.repo.SqlRepo::class"))
        assertEquals("SqlRepo::def save(self, o)", model.block("callee:shop.repo::SqlRepo.save").title)
        val cls = model.block("callee:shop.repo.SqlRepo::class")
        assertFalse(cls.collapsed)
        assertEquals("class SqlRepo", cls.title)
    }

    fun testAnAddedKeyThatNoLongerExistsIsLeftOut() {
        val model = model(service, added = setOf("shop.mail::gone", "shop.nope::x", "shop.repo::Repo.gone", "shop.repo.Nope::class"))
        assertTrue(model.ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testACallToAnAbstractMethodShowsItsImplementations() {
        val model = model(service, followed = setOf("method:Service.place"))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "callee:shop.repo::Repo.save", "callee:shop.repo::SqlRepo.save")))
        assertEquals("SqlRepo::def save(self, o)", model.block("callee:shop.repo::SqlRepo.save").title)
    }

    fun testACallToAConcreteMethodShowsNoImplementations() {
        val model = model("""
            from shop.repo import Repo

            def run(repo: Repo):
                repo.count()
        """, path = "shop/run.py", followed = setOf("method:run"))
        assertEquals(setOf("callee:shop.repo::Repo.count"), model.ids(BlockKind.CALLEE))
        assertTrue(model.links.none { it.kind == LinkKind.IMPLEMENTED_BY })
    }

    fun testAnAbstractClassShowsItsImplementersInOtherModules() {
        addModule("shop/mongo.py", """
            from shop.repo import Repo

            class MongoRepo(Repo):
                def save(self, o):
                    pass

                def extra(self):
                    pass
        """)
        val own = existing("shop/repo.py")
        // SqlRepo lives in the same module: it is already a class block, linked class to class.
        assertTrue(own.links.contains(Link(LinkKind.IMPLEMENTED_BY, "class:shop.repo.Repo", "class:shop.repo.SqlRepo")))
        assertEquals(setOf("implementer:shop.mongo.MongoRepo"), own.ids(BlockKind.IMPLEMENTER))
        val implementer = own.block("implementer:shop.mongo.MongoRepo")
        assertEquals(listOf("def save(self, o)"), implementer.summary)
        assertTrue(implementer.collapsed)
        assertTrue(own.links.contains(Link(LinkKind.IMPLEMENTED_BY, "class:shop.repo.Repo", "implementer:shop.mongo.MongoRepo")))
        val implementation = own.block("implementation:shop.mongo::MongoRepo.save")
        assertEquals(BlockKind.IMPLEMENTATION, implementation.kind)
        assertEquals("MongoRepo::def save(self, o)", implementation.title)
        assertTrue(own.links.contains(Link(LinkKind.OWNS, "implementer:shop.mongo.MongoRepo", "implementation:shop.mongo::MongoRepo.save")))
        assertTrue(own.links.contains(Link(LinkKind.OVERRIDES, "implementation:shop.mongo::MongoRepo.save", "method:Repo.save",
            own.block("method:Repo.save").range)))
    }

    fun testAProtocolShowsItsImplementers() {
        addModule("shop/sender.py", """
            from shop.ports import Sender

            class SmtpSender(Sender):
                def send(self, o): pass
        """)
        val model = model("""
            from typing import Protocol

            class Sender(Protocol):
                def send(self, o): ...
        """, path = "shop/ports.py")
        assertEquals(setOf("implementer:shop.sender.SmtpSender"), model.ids(BlockKind.IMPLEMENTER))
    }

    fun testAPlainClassShowsNoImplementers() {
        addModule("shop/sub.py", """
            from shop.plain import Plain

            class Sub(Plain):
                def go(self): pass
        """)
        val model = model("""
            class Plain:
                def go(self): pass
        """, path = "shop/plain.py")
        assertTrue(model.ofKind(BlockKind.IMPLEMENTER).isEmpty())
    }

    fun testImplementersStopAtTheCap() {
        (1..12).forEach { addModule("shop/impl$it.py", "from shop.repo import Repo\n\nclass Impl$it(Repo):\n    def save(self, o): pass\n") }
        val model = existing("shop/repo.py")
        assertEquals(10, model.ofKind(BlockKind.IMPLEMENTER).size)
        assertEquals("and 2 more", model.block(PythonBlockBuilder.MORE_ID).title)
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "class:shop.repo.Repo", PythonBlockBuilder.MORE_ID)))
    }

    fun testTheChainStopsAtTheCap() {
        (1..12).forEach { addModule("shop/c$it.py", "def go():\n    pass\n") }
        val imports = (1..12).joinToString("\n") { "import shop.c$it" }
        val calls = (1..12).joinToString("\n") { "    shop.c$it.go()" }
        val model = model("$imports\n\ndef run():\n$calls\n", path = "shop/big.py", followed = setOf("method:run"))
        assertEquals(10, model.ofKind(BlockKind.CALLEE).size)
        assertEquals("and 2 more calls", model.block("more:calls:method:run").title)
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:run", "more:calls:method:run")))
    }

    // Follow-ups from Task 4.

    fun testGenericAndProtocolThatAreNotClassesAreNotParents() {
        val model = model("""
            from typing import Generic, Protocol, TypeVar
            from abc import ABC
            from shop.repo import SqlRepo

            T = TypeVar("T")

            class A(Generic[T], SqlRepo): pass
            class B(Protocol): pass
            class C(ABC): pass
            class D(object): pass
        """, path = "shop/views.py")
        assertEquals(setOf("parent:shop.repo.SqlRepo"), model.ids(BlockKind.PARENT))
    }

    fun testAProjectClassNamedABCIsAParent() {
        addModule("shop/letters.py", "class ABC:\n    def a(self): pass\n")
        val model = model("""
            from shop.letters import ABC

            class C(ABC): pass
        """, path = "shop/views.py")
        assertEquals(setOf("parent:shop.letters.ABC"), model.ids(BlockKind.PARENT))
    }

    fun testRevealingAClassOfTheOpenModuleWhileDiggingIsHarmless() {
        // shop.other.X builds on a class of the open module: + parents on X reaches class:shop.views.A.
        addModule("shop/other.py", """
            from shop.views import A

            class X(A): pass
        """)
        val model = model("""
            from shop.other import X

            class A: pass

            class B(X): pass
        """, path = "shop/views.py", revealed = setOf("parent:shop.other.X", "class:shop.views.A"))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "class:shop.views.A", "parent:shop.other.X")))
    }

    private companion object {
        /** A library root (outside the project's content): `extlib` (site-packages) and `abc` (standard library). */
        val LIBRARY_DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val dir = Files.createTempDirectory("codelanes-pylib")
                Files.createDirectories(dir.resolve("extlib"))
                Files.writeString(dir.resolve("extlib/__init__.py"), "")
                Files.writeString(dir.resolve("extlib/views.py"), "def helper():\n    pass\n")
                Files.writeString(dir.resolve("_forms.py"), "class Generic:\n    pass\n")
                Files.writeString(dir.resolve("abc.py"), "class ABC:\n    pass\n\ndef abstractmethod(f):\n    return f\n")
                val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
                val library = model.moduleLibraryTable.createLibrary("pylib")
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.CLASSES)
                    commit()
                }
            }
        }
    }
}
