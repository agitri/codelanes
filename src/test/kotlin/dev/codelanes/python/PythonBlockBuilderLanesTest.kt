package dev.codelanes.python

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.jetbrains.python.psi.PyFile
import dev.codelanes.layout.LayoutEngine
import dev.codelanes.layout.Size
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import java.nio.file.Files

class PythonBlockBuilderLanesTest : PythonTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LIBRARY_DESCRIPTOR

    override fun setUp() {
        super.setUp()
        addModule("shop/__init__.py", "")
        // No Python SDK: just enough of typing/dataclasses/pydantic for the annotations and decorators below.
        addModule("typing.py", """
            Optional = object()
            Annotated = object()
            ClassVar = object()
            class Generic: pass
            class Protocol: pass
        """)
        addModule("dataclasses.py", "def dataclass(cls): return cls")
        addModule("pydantic/__init__.py", "class BaseModel: pass")
        addModule("shop/base.py", """
            class Root:
                def boot(self): pass

            class Base(Root):
                def save(self): pass
                def load(self, key: str): pass

            class Mixin:
                def mix(self): pass
        """)
        addModule("shop/deps.py", """
            class Repo: pass
            class Mailer: pass
            class Item: pass
            class Clock: pass
        """)
    }

    private fun model(text: String, revealed: Set<String> = emptySet(), path: String = "shop/views.py"): BlockModel {
        val file = open(path, text) as PyFile
        return when (val result = PythonBlockBuilder.build(file, revealed, emptySet(), emptySet())) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }
    }

    private fun BlockModel.ids(kind: BlockKind) = ofKind(kind).map { it.id }.toSet()

    fun testBaseClassesAreParentBlocks() {
        val model = model("""
            from shop.base import Base, Mixin

            class A(Base, Mixin):
                pass
        """)
        assertEquals(setOf("parent:shop.base.Base", "parent:shop.base.Mixin"), model.ids(BlockKind.PARENT))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:shop.base.Base", "class:shop.views.A")))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:shop.base.Mixin", "class:shop.views.A")))
        val base = model.block("parent:shop.base.Base")
        assertTrue(base.collapsed)
        assertFalse(base.sourceless)
        assertEquals("class Base", base.title)
        assertEquals(listOf("def save(self)", "def load(self, key: str)"), base.summary)
        assertTrue(base.filePath.endsWith("shop/base.py"))
        assertTrue(base.range.end > base.range.start)
        // Root (Base's own parent) only comes with + parents.
        assertFalse(model.blocks.any { it.id == "parent:shop.base.Root" })
    }

    fun testPlusParentsDigsDeeper() {
        val model = model("""
            from shop.base import Base

            class A(Base):
                pass
        """, revealed = setOf("parent:shop.base.Base"))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:shop.base.Root", "parent:shop.base.Base")))
        assertEquals(listOf("def boot(self)"), model.block("parent:shop.base.Root").summary)
    }

    fun testObjectGenericAndProtocolAreNotParents() {
        val model = model("""
            from typing import Generic, Protocol
            from shop.base import Mixin

            class A(object): pass
            class B(Generic[T], Mixin): pass
            class C(Protocol): pass
        """)
        assertEquals(setOf("parent:shop.base.Mixin"), model.ids(BlockKind.PARENT))
    }

    fun testALibraryBaseIsSourceless() {
        val model = model("""
            from extlib.views import LibraryView

            class A(LibraryView):
                def get(self): pass
        """)
        val parent = model.block("parent:extlib.views.LibraryView")
        assertTrue(parent.sourceless)
        assertTrue(parent.collapsed)
        assertEquals(listOf("def get(self)"), parent.summary)
        val link = model.links.single { it.kind == LinkKind.OVERRIDES }
        assertEquals("method:A.get", link.from)
        assertEquals("parent:extlib.views.LibraryView", link.to)
        assertNull(link.targetRange)
    }

    fun testABaseInTheSameModuleIsLinkedClassToClass() {
        val model = model("""
            class A:
                def save(self): pass

            class B(A):
                def save(self): pass
        """)
        assertTrue(model.ofKind(BlockKind.PARENT).isEmpty())
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "class:shop.views.A", "class:shop.views.B")))
        val link = model.links.single { it.kind == LinkKind.OVERRIDES }
        assertEquals("method:B.save", link.from)
        assertEquals("class:shop.views.A", link.to)
    }

    fun testInitParametersAreInjected() {
        val model = model("""
            from typing import Optional, Annotated
            from shop.deps import Repo, Mailer, Item, Clock

            class S:
                def __init__(self, repo: Repo, mailer: Optional[Mailer], name: str, items: list[Item],
                             clock: Annotated[Clock, "x"], again: Repo | None, untyped):
                    pass
        """)
        val expected = setOf("dependency:shop.deps.Repo", "dependency:shop.deps.Mailer", "dependency:shop.deps.Item", "dependency:shop.deps.Clock")
        assertEquals(expected, model.ids(BlockKind.DEPENDENCY))
        assertEquals(4, model.ofKind(BlockKind.DEPENDENCY).size)
        expected.forEach { assertTrue(it, model.links.contains(Link(LinkKind.INJECTS, it, "class:shop.views.S"))) }
        assertEquals(4, model.links.count { it.kind == LinkKind.INJECTS })
        assertTrue(model.block("dependency:shop.deps.Repo").collapsed)
    }

    fun testAnnotatedOnlyCountsItsFirstArgument() {
        val model = model("""
            from typing import Annotated
            from shop.deps import Clock, Mailer

            class S:
                def __init__(self, clock: Annotated[Clock, Mailer]): pass
        """)
        assertEquals(setOf("dependency:shop.deps.Clock"), model.ids(BlockKind.DEPENDENCY))
    }

    fun testDataclassFieldsAreInjected() {
        val model = model("""
            from dataclasses import dataclass
            from shop.deps import Repo, Clock

            @dataclass
            class C:
                repo: Repo
                clock: Clock = None
                count: int = 0

                def __init__(self): pass
        """)
        assertEquals(setOf("dependency:shop.deps.Repo", "dependency:shop.deps.Clock"), model.ids(BlockKind.DEPENDENCY))
    }

    fun testPydanticFieldsAreInjected() {
        val model = model("""
            from pydantic import BaseModel
            from shop.deps import Repo

            class S(BaseModel):
                repo: Repo
        """)
        assertEquals(setOf("dependency:shop.deps.Repo"), model.ids(BlockKind.DEPENDENCY))
        assertTrue(model.links.contains(Link(LinkKind.INJECTS, "dependency:shop.deps.Repo", "class:shop.views.S")))
    }

    fun testAnnotatedAttributesOfAPlainClassWithInitAreNotInjected() {
        val model = model("""
            from shop.deps import Repo, Clock

            class S:
                repo: Repo
                def __init__(self, clock: Clock): pass
        """)
        assertEquals(setOf("dependency:shop.deps.Clock"), model.ids(BlockKind.DEPENDENCY))
    }

    fun testOverridesLinkToTheParentThatDefinesTheMethod() {
        val model = model("""
            from shop.base import Base, Mixin

            class B(Base, Mixin):
                def save(self): pass
                def other(self): pass
        """)
        val link = model.links.single { it.kind == LinkKind.OVERRIDES }
        assertEquals("method:B.save", link.from)
        assertEquals("parent:shop.base.Base", link.to)
        val baseFile = myFixture.findFileInTempDir("shop/base.py")
        val text = String(baseFile.contentsToByteArray())
        assertEquals("def save(self): pass", text.substring(link.targetRange!!.start, link.targetRange!!.end))
    }

    fun testCallsBetweenFunctions() {
        val model = model("""
            def a():
                b()

            def b():
                pass
        """)
        assertEquals(listOf(Link(LinkKind.CALLS, "method:a", "method:b")), model.links.filter { it.kind == LinkKind.CALLS })
    }

    fun testSelfCallsInsideAClass() {
        val model = model("""
            class C:
                def place(self):
                    self.check()
                    self.place()

                def check(self):
                    pass
        """)
        assertEquals(listOf(Link(LinkKind.CALLS, "method:C.place", "method:C.check")), model.links.filter { it.kind == LinkKind.CALLS })
    }

    fun testUnresolvedCallsAreSkipped() {
        val model = model("""
            def a(x):
                x.whatever()
                nowhere()
        """)
        assertTrue(model.links.none { it.kind == LinkKind.CALLS })
    }

    /** Two classes with their own parents: the first class's parents sit above the second's, so lines don't cross. */
    fun testLeftLanesFollowTheClassOrder() {
        addModule("shop/other.py", """
            class First: pass
            class Second: pass
        """)
        val model = model("""
            from shop.other import First, Second

            def helper():
                pass

            class A(First):
                def a(self): pass
                def b(self): pass
                def c(self): pass

            class B(Second):
                def d(self): pass
        """)
        val rects = LayoutEngine.layout(model, { if (it.kind == BlockKind.CLASS || it.kind == BlockKind.MODULE) Size(300, 160) else Size(200, 60) }).rects
        assertTrue(rects.getValue("parent:shop.other.First").y < rects.getValue("parent:shop.other.Second").y)
    }

    private companion object {
        /** A library root (outside the project's content) holding `extlib/views.py`, for sourceless parents. */
        val LIBRARY_DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val dir = Files.createTempDirectory("codelanes-pylib")
                Files.createDirectories(dir.resolve("extlib"))
                Files.writeString(dir.resolve("extlib/__init__.py"), "")
                Files.writeString(dir.resolve("extlib/views.py"), "class LibraryView:\n    def get(self):\n        pass\n")
                val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
                val library = model.moduleLibraryTable.createLibrary("extlib")
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.CLASSES)
                    commit()
                }
            }
        }
    }
}
