package dev.codelanes.python

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Python fixture tests: a light project whose source root is the fixture's temp dir, without a Python SDK
 * (the standard library doesn't resolve: stub what a test needs with [addModule]).
 */
abstract class PythonTestCase : BasePlatformTestCase() {
    /** Adds [path] (e.g. "shop/repo.py") under the source root. */
    protected fun addModule(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text.trimIndent())

    /** Adds [path] and opens it in the editor. */
    protected fun open(path: String, text: String): PsiFile {
        val file = addModule(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }
}
