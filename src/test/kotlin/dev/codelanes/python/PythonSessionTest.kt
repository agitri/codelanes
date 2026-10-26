package dev.codelanes.python

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightProjectDescriptor
import com.jetbrains.python.psi.PyFile
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.flow.FlowResolver
import dev.codelanes.lang.FlowTarget
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BuildResult
import dev.codelanes.settings.PinStore

/** A Python module driven through the same session the PHP and Java canvases use. */
class PythonSessionTest : PythonTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = PythonBlockBuilderCallsTest.LIBRARY_DESCRIPTOR

    private var session: BlocksSession? = null

    /** Release the session's editors before the fixture checks for unreleased ones. */
    override fun tearDown() {
        try {
            session?.let { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun openSession(path: String, text: String): BlocksSession {
        val psi = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(psi.virtualFile)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        return BlocksSession(project, psi.virtualFile).also { session = it }
    }

    private fun text(): String = FileDocumentManager.getInstance().getDocument(myFixture.file.virtualFile)!!.text

    fun testPlusMethodOnTheModuleAppendsAFunctionWithItsNameSelected() {
        val session = openSession("svc.py", "def a():\n    pass\n")
        session.addMethod("module:svc")
        assertEquals("def a():\n    pass\n\n\ndef new_function():\n    pass\n", text())
        assertTrue(session.blockIds().contains("method:new_function"))
        assertEquals("new_function", session.sliceEditor("method:new_function")!!.editor.selectionModel.selectedText)
    }

    fun testPlusMethodWithoutAnOwnerUsesTheModule() {
        val session = openSession("svc.py", "def a():\n    pass\n")
        session.addMethod()
        assertTrue(session.blockIds().contains("method:new_function"))
    }

    fun testPlusMethodOnAClassInsertsAMethodInsideIt() {
        val session = openSession("svc.py", "class C:\n    def a(self):\n        pass\n\nx = 1\n")
        session.addMethod("class:svc.C")
        assertEquals("class C:\n    def a(self):\n        pass\n\n    def new_method(self):\n        pass\n\nx = 1\n", text())
        assertTrue(session.blockIds().contains("method:C.new_method"))
        assertEquals("new_method", session.sliceEditor("method:C.new_method")!!.editor.selectionModel.selectedText)
        session.addMethod("class:svc.C")
        assertTrue(session.blockIds().contains("method:C.new_method2"))
        assertTrue(text().contains("    def new_method(self):\n        pass\n\n    def new_method2(self):\n        pass\n\nx = 1\n"))
    }

    fun testTheClassButtonAddsToThatClass() {
        val session = openSession("svc.py", "class A:\n    pass\n\n\nclass B:\n    def b(self):\n        pass\n")
        assertEquals(listOf("+ method"), session.view("module:svc")!!.actionTexts())
        session.view("class:svc.B")!!.clickAction("+ method")
        assertTrue(session.blockIds().contains("method:B.new_method"))
        assertTrue(text().endsWith("class B:\n    def b(self):\n        pass\n\n    def new_method(self):\n        pass\n"))
    }

    fun testDeleteRemovesExactlyTheMethodsLines() {
        val session = openSession("svc.py", "class C:\n    def a(self):\n        pass\n\n    def b(self):\n        pass\n\nx = 1\n")
        session.deleteMethod("method:C.a") { true }
        assertEquals("class C:\n\n    def b(self):\n        pass\n\nx = 1\n", text())
        assertFalse(session.blockIds().contains("method:C.a"))
        assertTrue(session.blockIds().contains("method:C.b"))
    }

    fun testDeleteAModuleFunction() {
        val session = openSession("svc.py", "def a():\n    pass\n\n\ndef b():\n    pass\n")
        session.deleteMethod("method:b") { true }
        assertEquals("def a():\n    pass\n", text())
    }

    fun testAddingAFunctionOfTheOpenModuleFocusesItsBlockInsteadOfDuplicatingIt() {
        val session = openSession("svc.py", "class C:\n    def a(self):\n        pass\n\n\ndef f():\n    pass\n")
        session.addToCanvas("svc", "C.a")
        assertFalse(session.blockIds().any { it.startsWith("callee:") })
        assertEquals("method:C.a", session.canvas.focusedId)
        session.addToCanvas("svc", "f")
        assertFalse(session.blockIds().any { it.startsWith("callee:") })
        assertEquals("method:f", session.canvas.focusedId)
    }

    fun testAnAddedKeyIntoTheOpenModuleMakesNoCalleeBlock() {
        val file = open("svc.py", "class C:\n    def a(self):\n        pass\n\n\ndef f():\n    pass\n") as PyFile
        val model = (PythonBlockBuilder.build(file, emptySet(), emptySet(), setOf("svc::f", "svc::C.a", "svc.C::class")) as BuildResult.Supported).model
        assertTrue(model.ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testFlowIdsOfPythonTargets() {
        addModule("shop/__init__.py", "")
        val service = addModule("shop/service.py", "class Service:\n    def run(self):\n        pass\n") as PyFile
        val repo = addModule("shop/repo.py", "class Repo:\n    def save(self):\n        pass\n") as PyFile
        val support = PythonLanguageSupport()
        val serviceClass = service.findTopLevelClass("Service")!!
        val repoClass = repo.findTopLevelClass("Repo")!!
        val classTarget = FlowTarget("shop.service", "shop.service", null, false, service, serviceClass, null, support)
        val save = repoClass.findMethodByName("save", false, null)!!
        val saveTarget = FlowTarget("shop.repo", "shop.repo", "Repo.save", false, repo, repoClass, save, support)
        val run = serviceClass.findMethodByName("run", false, null)!!
        val runTarget = FlowTarget("shop.service", "shop.service", "Service.run", false, service, serviceClass, run, support)
        val repoTarget = FlowTarget("shop.repo", "shop.repo", null, false, repo, repoClass, null, support)
        val added = linkedSetOf<String>()
        assertEquals("class:shop.service.Service", FlowResolver.idOf(classTarget, "shop.service", added))
        assertEquals("method:Service.run", FlowResolver.idOf(runTarget, "shop.service", added))
        assertEquals("callee:shop.repo::Repo.save", FlowResolver.idOf(saveTarget, "shop.service", added))
        assertEquals("callee:shop.repo.Repo::class", FlowResolver.idOf(repoTarget, "shop.service", added))
        assertEquals(setOf("shop.repo::Repo.save", "shop.repo.Repo::class"), added)
    }

    fun testMethodFinderListsProjectFunctionsAndMethodsOnly() {
        addModule("shop/__init__.py", "")
        addModule("shop/repo.py", "class Repo:\n    def save(self):\n        pass\n\n\ndef helper():\n    pass\n")
        addModule("shop/stub.pyi", "def typed() -> int: ...\n")
        val refs = PythonMethodFinder.find(project)
        val labels = refs.map { it.label }
        assertTrue(labels.toString(), labels.contains("repo::Repo.save  shop.repo"))
        assertTrue(labels.toString(), labels.contains("repo::helper  shop.repo"))
        assertTrue(refs.contains(dev.codelanes.lang.MethodRef("repo::Repo.save  shop.repo", "shop.repo", "Repo.save")))
        assertFalse(labels.toString(), labels.any { "helper  extlib" in it || "views" in it || "typed" in it })
        assertEquals(labels.sorted(), labels)
    }

    fun testPlusMethodFollowsTheClassBodysOwnIndent() {
        val session = openSession("svc.py", "class C:\n  def a(self):\n    pass\n")
        session.addMethod("class:svc.C")
        assertEquals("class C:\n  def a(self):\n    pass\n\n  def new_method(self):\n    pass\n", text())
        assertTrue(session.blockIds().contains("method:C.new_method"))
    }

    fun testPlusMethodOnAOneLineClassMovesTheBodyDown() {
        val session = openSession("svc.py", "class E(Exception): pass\n\n\nclass F: ...\n\n\nclass G: x = 1\n")
        session.addMethod("class:svc.E")
        session.addMethod("class:svc.F")
        session.addMethod("class:svc.G")
        assertEquals(
            "class E(Exception):\n    def new_method(self):\n        pass\n\n\n" +
                "class F:\n    def new_method(self):\n        pass\n\n\n" +
                "class G:\n    x = 1\n\n    def new_method(self):\n        pass\n",
            text(),
        )
        assertTrue(session.blockIds().containsAll(listOf("method:E.new_method", "method:F.new_method", "method:G.new_method")))
        assertEquals("new_method", session.sliceEditor("method:G.new_method")!!.editor.selectionModel.selectedText)
    }

    fun testFollowingARouteToAClassWithoutThatMethodRevealsTheClass() {
        val session = openSession("cbv.py", "def helper():\n    pass\n\n\nclass Base:\n    pass\n\n\nclass OrderList(Base):\n    model = None\n")
        session.followChain("method:OrderList", 3)
        assertEquals("class:cbv.OrderList", session.canvas.focusedId)
    }

    fun testPlusMethodOnTheModuleGoesAboveTheMainGuard() {
        val session = openSession("svc.py", "def a():\n    pass\n\n\nif __name__ == \"__main__\":\n    a()\n")
        session.addMethod("module:svc")
        assertEquals("def a():\n    pass\n\n\ndef new_function():\n    pass\n\n\nif __name__ == \"__main__\":\n    a()\n", text())
        assertTrue(session.blockIds().contains("method:new_function"))
    }

    fun testPlusMethodAboveAMainGuardWithOneBlankLineKeepsTwo() {
        val session = openSession("svc.py", "def a():\n    pass\n\nif __name__ == '__main__':\n    a()\n")
        session.addMethod("module:svc")
        assertEquals("def a():\n    pass\n\n\ndef new_function():\n    pass\n\n\nif __name__ == '__main__':\n    a()\n", text())
    }

    fun testDeletingTheOnlyMethodOfAClassLeavesPass() {
        val session = openSession("svc.py", "class C:\n    def a(self):\n        pass\n")
        session.deleteMethod("method:C.a") { true }
        assertEquals("class C:\n    pass\n", text())
        assertTrue(session.blockIds().contains("class:svc.C"))
    }

    fun testDeletingOneOfTwoMethodsAddsNoPass() {
        val session = openSession("svc.py", "class C:\n    x = 1\n\n    def a(self):\n        pass\n")
        session.deleteMethod("method:C.a") { true }
        assertEquals("class C:\n    x = 1\n\n", text())
    }

    fun testMethodFinderListsExactlyTheCanvasMethods() {
        addModule("shop/__init__.py", "")
        val source = "import sys\n\nclass Repo:\n    if sys:\n        def hidden(self):\n            pass\n\n    def save(self):\n        def inner():\n            pass\n\n\nif sys:\n    def also_hidden():\n        pass\n\n\ndef helper():\n    pass\n"
        val file = addModule("shop/repo.py", source) as PyFile
        val model = (PythonBlockBuilder.build(file, emptySet(), emptySet(), emptySet()) as BuildResult.Supported).model
        val canvas = model.ofKind(BlockKind.METHOD).map { it.id.removePrefix("method:") }.sorted()
        val found = PythonMethodFinder.find(project).filter { it.classFqn == "shop.repo" }.map { it.method }.sorted()
        assertEquals(listOf("Repo.save", "helper"), canvas)
        assertEquals(canvas, found)
    }
}
