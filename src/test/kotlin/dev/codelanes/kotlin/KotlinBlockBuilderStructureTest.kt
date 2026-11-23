package dev.codelanes.kotlin

import dev.codelanes.layout.LayoutEngine
import dev.codelanes.layout.Size
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import org.jetbrains.kotlin.psi.KtFile

class KotlinBlockBuilderStructureTest : KotlinTestCase() {
    private fun model(path: String, text: String): BlockModel {
        val file = open(path, text) as KtFile
        return when (val result = inBackground { KotlinBlockBuilder.build(file, emptySet(), emptySet(), emptySet()) }) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }
    }

    /** The text of [block] as shown on the canvas: its range minus its excluded ranges. */
    private fun visibleText(block: Block): String {
        val text = myFixture.file.text
        return (block.range.start until block.range.end)
            .filter { offset -> block.excluded.none { it.contains(offset) } }
            .map { text[it] }
            .joinToString("")
    }

    private fun ids(model: BlockModel) = model.blocks.map { it.id }

    fun testAFileBecomesHeaderModuleClassAndFunctionBlocks() {
        val model = model("acme/notes/NoteScreen.kt", """
            package acme.notes

            import acme.Repo
            import acme.ViewModel

            const val MAX = 3

            fun NoteScreen(id: Long) {}

            class NoteViewModel(private val repo: Repo) {
                fun save() {}
                companion object { fun create() = 1 }
            }
        """)
        assertSameElements(
            ids(model),
            "header", "module:acme.notes.NoteScreen", "method:NoteScreen", "class:acme.notes.NoteViewModel",
            "method:NoteViewModel.save", "method:NoteViewModel.Companion.create",
        )
        assertEquals("package acme.notes · 2 imports", model.block("header").title)
        assertTrue(model.block("header").collapsed)
        assertEquals(BlockKind.HEADER, model.block("header").kind)
        assertEquals(BlockKind.MODULE, model.block("module:acme.notes.NoteScreen").kind)
        assertEquals(BlockKind.CLASS, model.block("class:acme.notes.NoteViewModel").kind)
        assertEquals(BlockKind.METHOD, model.block("method:NoteScreen").kind)
    }

    fun testOneClassFileHasNoModuleBlock() {
        val model = model("acme/Repo.kt", "package acme\n\nclass Repo { fun save() {} }")
        assertSameElements(ids(model), "header", "class:acme.Repo", "method:Repo.save")
        assertEquals("package acme", model.block("header").title)
    }

    fun testTwoClassesNoModule() {
        val model = model("acme/Two.kt", """
            package acme

            class A { fun a() {} }
            class B { fun b() {} }
        """)
        assertEquals(2, model.blocks.count { it.kind == BlockKind.CLASS })
        assertTrue(model.blocks.none { it.kind == BlockKind.MODULE })
        val layout = LayoutEngine.layout(model, { _: Block -> Size(200, 60) })
        assertEquals(model.blocks.size, layout.rects.size)
    }

    fun testEmptyFileShowsModuleBlock() {
        val model = model("acme/Empty.kt", "package acme")
        assertSameElements(ids(model), "header", "module:acme.Empty")
        assertEquals("package acme", model.block("header").title)
        assertTrue(model.block("header").collapsed)
    }

    fun testImportsOnlyHeader() {
        val model = model("Top.kt", "import a.B\n\nclass Top")
        assertEquals("1 import", model.block("header").title)
        assertSameElements(ids(model), "header", "class:Top")
    }

    fun testNoPackageNoImportNoHeader() {
        val model = model("Top.kt", "class Top")
        assertSameElements(ids(model), "class:Top")
    }

    fun testSameNameTopLevelAndMember() {
        val model = model("acme/Repo.kt", """
            package acme

            fun save() {}
            class Repo { fun save() {} }
        """)
        assertTrue(ids(model).containsAll(listOf("method:save", "method:Repo.save")))
    }

    fun testOverloadsAndExtensions() {
        val model = model("acme/Util.kt", """
            package acme

            fun save(x: Int) {}
            fun save(x: Int, y: String) {}
            fun String.slug() {}
            fun Int.slug() {}
        """)
        val methods = ids(model).filter { it.startsWith("method:") }
        assertSameElements(methods, "method:save(Int)", "method:save(Int, String)", "method:String.slug", "method:Int.slug")
    }

    fun testMemberOverloads() {
        val model = model("acme/Repo.kt", """
            package acme

            class Repo {
                fun put(x: Int) {}
                fun put(x: Int, y: String) {}
            }
        """)
        assertSameElements(ids(model).filter { it.startsWith("method:") }, "method:Repo.put(Int)", "method:Repo.put(Int, String)")
    }

    fun testFunctionKeyAndKeyword() {
        val file = open("acme/Repo.kt", """
            package acme

            class Repo {
                fun save() {}
                companion object { fun create() = 1 }
            }
            fun top() {}
        """) as KtFile
        val functions = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, org.jetbrains.kotlin.psi.KtNamedFunction::class.java)
        assertSameElements(functions.map(KotlinBlockBuilder::functionKey), "Repo.save", "Repo.Companion.create", "top")
        assertEquals("class", KotlinBlockBuilder.classKeyword(file.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtClass>().first()))
    }

    fun testTitles() {
        val model = model("acme/Titles.kt", """
            package acme

            class NoteViewModel(private val repo: Repo) : ViewModel()
            object Store : Repo
            data class D(val a: Int)
            sealed interface S
            enum class E { A }
            value class V(val v: Int)
            annotation class An
            interface I

            suspend fun load(id: Long): Note = TODO()
            @Composable fun Screen(state: UiState) {}
            fun String.slug(): String = this
        """)
        assertEquals("class NoteViewModel(private val repo: Repo) : ViewModel()", model.block("class:acme.NoteViewModel").title)
        assertEquals("object Store : Repo", model.block("class:acme.Store").title)
        assertEquals("data class D(val a: Int)", model.block("class:acme.D").title)
        assertEquals("sealed interface S", model.block("class:acme.S").title)
        assertEquals("enum class E", model.block("class:acme.E").title)
        assertEquals("value class V(val v: Int)", model.block("class:acme.V").title)
        assertEquals("annotation class An", model.block("class:acme.An").title)
        assertEquals("interface I", model.block("class:acme.I").title)
        assertEquals("suspend fun load(id: Long): Note", model.block("method:load").title)
        assertEquals("@Composable fun Screen(state: UiState)", model.block("method:Screen").title)
        assertEquals("fun String.slug(): String", model.block("method:slug").title)
    }

    fun testRanges() {
        val model = model("acme/Ranges.kt", """
            package acme

            import a.B

            const val MAX = 3

            /** Docs for top. */
            @Composable
            fun top() {}

            /** Docs for Repo. */
            @Suppress("x")
            class Repo {
                val field = 1
                init { println() }
                constructor(x: Int) : this()
                class Nested { fun inner() {} }

                /** Docs for save. */
                @Deprecated("y")
                fun save() { fun local() {} }
            }
        """)
        val repo = visibleText(model.block("class:acme.Repo"))
        assertTrue(repo.contains("/** Docs for Repo. */"))
        assertTrue(repo.contains("@Suppress(\"x\")"))
        assertTrue(repo.contains("val field = 1"))
        assertTrue(repo.contains("init { println() }"))
        assertTrue(repo.contains("constructor(x: Int)"))
        assertTrue(repo.contains("class Nested { fun inner() {} }"))
        assertFalse(repo.contains("fun save"))
        assertFalse(repo.contains("Docs for save"))
        val save = visibleText(model.block("method:Repo.save"))
        assertTrue(save.contains("/** Docs for save. */"))
        assertTrue(save.contains("@Deprecated(\"y\")"))
        assertTrue(save.contains("fun local() {}"))
        assertSameElements(ids(model).filter { it.startsWith("method:") }, "method:top", "method:Repo.save")
        val module = visibleText(model.block("module:acme.Ranges"))
        assertTrue(module.contains("const val MAX = 3"))
        assertFalse(module.contains("import a.B"))
        assertFalse(module.contains("fun top"))
        assertFalse(module.contains("class Repo"))
        assertFalse(module.contains("Docs for top"))
        assertTrue(visibleText(model.block("method:top")).startsWith("/** Docs for top. */"))
    }
}
