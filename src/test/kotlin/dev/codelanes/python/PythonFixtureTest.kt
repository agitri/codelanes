package dev.codelanes.python

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.resolve.QualifiedNameFinder
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.TypeEvalContext
import dev.codelanes.lang.LanguageSupports

class PythonFixtureTest : PythonTestCase() {
    fun testACallResolvesIntoAnotherModule() {
        addModule("shop/__init__.py", "")
        addModule("shop/repo.py", """
            class Repo:
                def save(self, order):
                    pass
        """)
        val file = open("shop/service.py", """
            from shop.repo import Repo

            class Service:
                def __init__(self, repo: Repo):
                    self.repo = repo

                def place(self):
                    self.repo.save(1)
        """)
        val call = PsiTreeUtil.findChildrenOfType(file, PyCallExpression::class.java).single { it.text.startsWith("self.repo.save") }
        val context = TypeEvalContext.codeAnalysis(project, file)
        val resolved = call.multiResolveCalleeFunction(PyResolveContext.defaultContext(context)).single()
        assertEquals("save", resolved.name)
        assertEquals("Repo", (resolved as PyFunction).containingClass?.name)
    }

    /** Dotted module names come from the file's place under the source root; classes and functions add their own. */
    fun testQualifiedNames() {
        addModule("shop/__init__.py", "")
        val file = open("shop/repo.py", """
            class Repo:
                def save(self, order):
                    pass

            def helper():
                pass
        """) as PyFile
        assertEquals("shop.repo", QualifiedNameFinder.findShortestImportableName(file, file.virtualFile))
        val repo = file.findTopLevelClass("Repo")!!
        assertEquals("shop.repo.Repo", repo.qualifiedName)
        assertEquals("shop.repo.Repo.save", repo.findMethodByName("save", false, null)!!.qualifiedName)
        assertEquals("shop.repo.helper", file.findTopLevelFunction("helper")!!.qualifiedName)
    }

    fun testSupportHandlesPyAndRejectsStubs() {
        val support = PythonLanguageSupport()
        assertTrue(support.handles(open("a.py", "x = 1").virtualFile))
        assertEquals("Stub file", support.unsupportedReason(open("b.pyi", "x: int")))
    }

    /** The codelanes.python content module loads (its plugin and module dependencies resolve) and registers the support. */
    fun testThePythonModuleIsRegistered() {
        assertTrue(LanguageSupports.forFile(open("c.py", "x = 1").virtualFile) is PythonLanguageSupport)
    }
}
