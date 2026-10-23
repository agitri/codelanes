package dev.codelanes.java

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.settings.PinStore
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** junit.jar with a sources jar attached: a library parent then has source to show, read-only. */
class JavaLibrarySourcesTest : JavaBuilderTestCase() {
    private var session: BlocksSession? = null

    override fun getProjectDescriptor(): LightProjectDescriptor = SOURCES_DESCRIPTOR

    override fun tearDown() {
        try {
            session?.let { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    fun testALibraryParentWithSourcesShowsThem() {
        val psi = myFixture.configureByText("MyTest.java", "package com.acme;\npublic class MyTest extends junit.framework.TestCase {\n}\n")
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        val session = BlocksSession(project, psi.virtualFile).also { session = it }
        val id = "parent:junit.framework.TestCase"
        session.collapseToggled(id)
        assertTrue(session.hasSliceEditor(id))
        assertFalse(session.sliceEditor(id)!!.editor.document.isWritable) // typing gets the platform's read-only hint
    }

    fun testLibrarySourcesDontOpenAsBlocks() {
        val source = JarFileSystem.getInstance().refreshAndFindFileByPath("${SOURCES_JAR.path}!/junit/framework/TestCase.java")!!
        val psi = PsiManager.getInstance(project).findFile(source)!!
        assertEquals("Library code", JavaBlockBuilder.unsupportedReason(psi))
    }

    private companion object {
        /** A sources jar for junit.framework.TestCase, written once per run. */
        val SOURCES_JAR: File by lazy {
            val jar = Files.createTempDirectory("codelanes-sources").resolve("junit-sources.jar").toFile()
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("junit/framework/TestCase.java"))
                zip.write("package junit.framework;\npublic abstract class TestCase extends Assert implements Test {\n    protected void setUp() throws Exception {}\n}\n".toByteArray())
                zip.closeEntry()
            }
            jar.deleteOnExit()
            jar
        }

        val SOURCES_DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val classes = File(PathManager.getJarPathForClass(junit.framework.TestCase::class.java)!!)
                val jars = JarFileSystem.getInstance()
                PsiTestUtil.newLibrary("junit")
                    .classesRoot(jars.refreshAndFindFileByPath("${classes.path}!/")!!)
                    .sourceRoot(jars.refreshAndFindFileByPath("${SOURCES_JAR.path}!/")!!)
                    .addTo(model)
            }
        }
    }
}
