package dev.codelanes.java

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.codelanes.model.Block
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import java.io.File

/**
 * Java fixture tests. There is no JDK here (java.lang doesn't resolve): stub what a test needs with [addClass].
 * junit.jar is on the module as a library without sources, for "sourceless" parents.
 */
abstract class JavaBuilderTestCase : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LIBRARY_DESCRIPTOR

    protected fun build(text: String, fileName: String = "Foo.java", revealed: Set<String> = emptySet(),
                        followed: Set<String> = emptySet(), added: Set<String> = emptySet()): BuildResult =
        JavaBlockBuilder.build(myFixture.configureByText(fileName, text), revealed, followed, added)

    protected fun supported(text: String, fileName: String = "Foo.java", revealed: Set<String> = emptySet(),
                            followed: Set<String> = emptySet(), added: Set<String> = emptySet()): BlockModel =
        when (val result = build(text, fileName, revealed, followed, added)) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }

    protected fun addClass(text: String) {
        myFixture.addClass(text.trimIndent())
    }

    /** The text of [block] as shown on the canvas: its range minus its excluded ranges. */
    protected fun visibleText(block: Block): String {
        val text = myFixture.file.text
        return (block.range.start until block.range.end)
            .filter { offset -> block.excluded.none { it.contains(offset) } }
            .map { text[it] }
            .joinToString("")
    }

    /** The annotations Spring code uses, as stubs (no Spring jar in the fixture). */
    protected fun addSpringStubs() {
        addClass("package org.springframework.stereotype; public @interface Service {}")
        addClass("package org.springframework.stereotype; public @interface Repository {}")
        addClass("package org.springframework.beans.factory.annotation; public @interface Autowired {}")
        addClass("package org.springframework.context.event; public @interface EventListener {}")
        addClass("package org.springframework.context; public interface ApplicationEventPublisher { void publishEvent(Object event); }")
        addClass("package lombok; public @interface RequiredArgsConstructor {}")
        addClass("package lombok; public @interface AllArgsConstructor {}")
        addClass("package javax.inject; public @interface Inject {}")
    }

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
