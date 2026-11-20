package dev.codelanes.kotlin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.application.PathManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

/** Kotlin fixture tests (K2 mode, see the test task in build.gradle.kts). */
abstract class KotlinTestCase : LightJavaCodeInsightFixtureTestCase() {
    /** No JDK and no Kotlin stdlib here; junit.jar is a library without sources, for "sourceless" supertypes. */
    override fun getProjectDescriptor(): LightProjectDescriptor = LIBRARY_DESCRIPTOR

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

    private companion object {
        val LIBRARY_DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val jar = File(PathManager.getJarPathForClass(junit.framework.TestCase::class.java)!!)
                PsiTestUtil.addLibrary(model, "junit", jar.parent, jar.name)
            }
        }
    }
}
