package dev.codelanes.kotlin

import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import com.intellij.psi.util.PsiTreeUtil

class KotlinBlockBuilderLanesTest : KotlinTestCase() {
    private fun model(path: String, text: String): BlockModel {
        val file = open(path, text) as KtFile
        return when (val result = inBackground { KotlinBlockBuilder.build(file, emptySet(), emptySet(), emptySet()) }) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }
    }

    private fun functionNamed(name: String, vararg paramTypes: String): KtNamedFunction =
        PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java)
            .first { f -> f.name == name && (paramTypes.isEmpty() || f.valueParameters.map { it.typeReference?.text } == paramTypes.toList()) }

    private fun links(model: BlockModel, kind: LinkKind) = model.links.filter { it.kind == kind }

    fun testSuperclassAndInterfaces() {
        addFile("acme/Base.kt", "package acme\nopen class Base(val x: Int)")
        addFile("acme/Foo.kt", "package acme\ninterface Foo")
        addFile("acme/Bar.kt", "package acme\ninterface Bar")
        val model = model("acme/A.kt", "package acme\nimport junit.framework.Test\nimport junit.framework.TestCase\nclass A : Base(1), Foo, Bar, Test\nclass T : TestCase()")
        assertEquals(BlockKind.PARENT, model.block("parent:acme.Base").kind)
        assertEquals(BlockKind.INTERFACE, model.block("interface:acme.Foo").kind)
        assertEquals(BlockKind.INTERFACE, model.block("interface:acme.Bar").kind)
        assertFalse(model.block("parent:acme.Base").sourceless)
        assertTrue(model.block("interface:junit.framework.Test").sourceless)
        assertTrue(model.block("parent:junit.framework.TestCase").sourceless)
        assertTrue(Link(LinkKind.EXTENDS, "parent:acme.Base", "class:acme.A") in links(model, LinkKind.EXTENDS))
        assertSameElements(
            links(model, LinkKind.IMPLEMENTS).map { it.from },
            "interface:acme.Foo", "interface:acme.Bar", "interface:junit.framework.Test",
        )
    }

    fun testJavaSuperclass() {
        val java = addFile("acme/JavaBase.java", "package acme;\npublic class JavaBase {}")
        val model = model("acme/A.kt", "package acme\nclass A : JavaBase()")
        val parent = model.block("parent:acme.JavaBase")
        assertEquals(java.virtualFile.path, parent.filePath)
        assertEquals(java.text.indexOf("public class"), parent.range.start)
        assertEquals(java.textLength, parent.range.end)
        assertFalse(parent.sourceless)
    }

    fun testDependencies() {
        for (name in listOf("Repo", "Store", "Log", "Bus", "Mailer", "NoteViewModel", "Clock")) {
            addFile("acme/$name.kt", "package acme\nclass $name")
        }
        // @Inject resolves (matched by qualified name); @Autowired stays unresolved (matched by short name).
        addFile("javax/inject/Inject.kt", "package javax.inject\nannotation class Inject")
        val model = model("acme/Screen.kt", """
            package acme

            import javax.inject.Inject

            class Screen(private val repo: Repo, store: Store?, val names: List<Repo>, n: Int, s: String) {
                constructor(log: Log, again: Repo) : this(again, null, emptyList(), 1, "")

                @Inject lateinit var bus: Bus
                @Autowired lateinit var mailer: Mailer
                private val vm: NoteViewModel by viewModels()
                val koin by inject<Clock>()
                val lazyRepo by lazy { 1 }
            }
        """)
        assertEquals(
            listOf("Repo", "Store", "Log", "Bus", "Mailer", "NoteViewModel", "Clock").map { "dependency:acme.$it" },
            model.ofKind(BlockKind.DEPENDENCY).map { it.id },
        )
        assertEquals(7, links(model, LinkKind.INJECTS).size)
        assertTrue(links(model, LinkKind.INJECTS).all { it.to == "class:acme.Screen" })
    }

    fun testInjectedTypesIsPublicAndSourceOrdered() {
        addFile("acme/Repo.kt", "package acme\nclass Repo")
        val file = open("acme/Screen.kt", "package acme\nclass Screen(a: Repo, b: Repo?)") as KtFile
        val cls = file.declarations.single() as KtClassOrObject
        assertEquals(listOf("acme.Repo"), KotlinBlockBuilder.injectedTypes(cls).map { (it as KtClassOrObject).fqName!!.asString() })
    }

    fun testCallsBetweenOwnFunctions() {
        val model = model("acme/Calls.kt", """
            package acme

            fun a() { b(); run { c() }; val f = ::d }
            fun b() {}
            fun c() {}
            fun d() {}
        """)
        assertSameElements(
            links(model, LinkKind.CALLS),
            Link(LinkKind.CALLS, "method:a", "method:b"), Link(LinkKind.CALLS, "method:a", "method:c"),
            Link(LinkKind.CALLS, "method:a", "method:d"),
        )
    }

    fun testConstructorCallLinksToClass() {
        val model = model("acme/Make.kt", "package acme\n\nclass Repo\nfun make() = Repo()")
        assertEquals(Link(LinkKind.CALLS, "method:make", "class:acme.Repo"), links(model, LinkKind.CALLS).single())
    }

    fun testOverrides() {
        addFile("acme/JBase.java", "package acme;\npublic class JBase { public void g() {} }")
        val model = model("acme/A.kt", """
            package acme

            open class Base { open fun f() {} }
            interface Foo { fun h(x: Int) }
            class A : Base(1), Foo {
                override fun f() {}
                override fun h(x: Int) {}
            }
            class B : JBase() {
                override fun g() {}
            }
        """)
        val base = (myFixture.file as KtFile).declarations.filterIsInstance<KtClassOrObject>().first { it.name == "Base" }.declarations.single() as KtNamedFunction
        val toBase = links(model, LinkKind.OVERRIDES).single { it.from == "method:A.f" }
        assertEquals("parent:acme.Base", toBase.to)
        assertEquals(base.textRange.startOffset, toBase.targetRange!!.start)
        assertEquals(base.textRange.endOffset, toBase.targetRange!!.end)
        val toFoo = links(model, LinkKind.OVERRIDES).single { it.from == "method:A.h" }
        assertEquals("interface:acme.Foo", toFoo.to)
        assertNotNull(toFoo.targetRange)
        val toJava = links(model, LinkKind.OVERRIDES).single { it.from == "method:B.g" }
        assertEquals("parent:acme.JBase", toJava.to)
        assertNotNull(toJava.targetRange)
    }

    fun testUnresolvedCallsAreSkipped() {
        val model = model("acme/U.kt", "package acme\n\nfun a() { missing(); x.y() }")
        assertEquals(emptyList<Link>(), links(model, LinkKind.CALLS))
    }

    fun testBuildDerivesMethodIdsThroughFunctionKey() {
        val model = model("acme/Keys.kt", """
            package acme

            class Repo {
                fun save() {}
                fun save(x: Int) {}
                companion object { fun create() = 1 }
            }
            fun String.slug() {}
            fun Int.slug() {}
        """)
        val methodIds = model.ofKind(BlockKind.METHOD).map { it.id }
        val keys = listOf(
            functionNamed("save", ).let { KotlinBlockBuilder.functionKey(it) },
            KotlinBlockBuilder.functionKey(functionNamed("save", "Int")),
            KotlinBlockBuilder.functionKey(functionNamed("create")),
            KotlinBlockBuilder.functionKey(PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java).first { it.receiverTypeReference?.text == "String" }),
        )
        assertEquals(listOf("Repo.save()", "Repo.save(Int)", "Repo.Companion.create", "String.slug"), keys)
        assertSameElements(methodIds, keys.map { "method:$it" } + "method:Int.slug")
    }

    fun testExtensionCollidingWithMemberGetsExtSuffix() {
        val model = model("acme/Ext.kt", """
            package acme

            class Repo { fun save() {} }
            fun Repo.save() {}
            fun save() {}
        """)
        val ids = model.ofKind(BlockKind.METHOD).map { it.id }
        assertSameElements(ids, "method:Repo.save", "method:Repo.save[ext]", "method:save")
        val ext = PsiTreeUtil.findChildrenOfType(myFixture.file, KtNamedFunction::class.java).first { it.receiverTypeReference != null }
        assertEquals("Repo.save[ext]", KotlinBlockBuilder.functionKey(ext))
    }

    fun testLoneExtensionKeepsPlainKey() {
        val model = model("acme/Ext2.kt", "package acme\n\nclass Repo { fun save() {} }\nfun Repo.save() {}")
        assertSameElements(model.ofKind(BlockKind.METHOD).map { it.id }, "method:Repo.save", "method:save")
    }
}
