package dev.codelanes.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** Kotlin fixture tests (K2 mode, see the test task in build.gradle.kts). */
abstract class KotlinTestCase : LightJavaCodeInsightFixtureTestCase() {
    /** Adds [path] (e.g. "acme/Repo.kt") under the source root. */
    protected fun addFile(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text.trimIndent())

    /** Adds [path] and opens it in the editor. */
    protected fun open(path: String, text: String): PsiFile {
        val file = addFile(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return myFixture.file
    }

    /**
     * Runs [block] in a read action on a pooled thread. Tests run on the EDT, where K2 refuses to analyze
     * (ProhibitedAnalysisException), e.g. in DefinitionsScopedSearch for overrides; production code never runs there.
     */
    protected fun <T> inBackground(block: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.compute<T, RuntimeException>(block) }.get()
}
