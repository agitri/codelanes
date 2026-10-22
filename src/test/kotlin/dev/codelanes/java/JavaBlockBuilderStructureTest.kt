package dev.codelanes.java

import dev.codelanes.lang.LanguageSupports
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BuildResult
import dev.codelanes.model.LinkKind

class JavaBlockBuilderStructureTest : JavaBuilderTestCase() {
    private val service = """
        package com.acme.order;

        import java.util.List;
        import com.acme.Thing;

        /** Places orders. */
        @Deprecated
        public class OrderService {
            private int count;

            OrderService() {}

            /** Places one. */
            public void place(String customer) {
                check();
            }

            private void check() {}
        }
    """.trimIndent()

    fun testAClassBecomesHeaderClassAndMethodBlocks() {
        val model = supported(service)
        assertEquals(listOf("header", "class:com.acme.order.OrderService", "method:place", "method:check"), model.blocks.map { it.id })
        assertEquals("package com.acme.order · 2 imports", model.block("header").title)
        assertTrue(model.block("header").collapsed)
        assertEquals("class OrderService", model.block("class:com.acme.order.OrderService").title)
        assertEquals("place(String customer)", model.block("method:place").title)
    }

    fun testJavadocAndAnnotationsBelongToTheirBlock() {
        val model = supported(service)
        assertTrue(visibleText(model.block("class:com.acme.order.OrderService")).startsWith("/** Places orders. */\n@Deprecated\npublic class"))
        assertTrue(visibleText(model.block("method:place")).startsWith("/** Places one. */"))
        // The constructor and the field stay in the class block; the methods don't.
        val classText = visibleText(model.block("class:com.acme.order.OrderService"))
        assertTrue(classText.contains("OrderService() {}"))
        assertFalse(classText.contains("check()"))
    }

    fun testMethodsAreOwnedAndOwnCallsAreLinked() {
        val model = supported(service)
        val cls = "class:com.acme.order.OrderService"
        assertTrue(model.links.contains(dev.codelanes.model.Link(LinkKind.OWNS, cls, "method:place")))
        assertTrue(model.links.contains(dev.codelanes.model.Link(LinkKind.CALLS, "method:place", "method:check")))
    }

    fun testOverloadsGetTheirParameterTypesInTheirIds() {
        val model = supported("""
            package a;
            class Repo {
                void save(Order o) {}
                void save(java.util.List<Order> all, boolean flush) {}
                void find() {}
            }
        """.trimIndent())
        assertEquals(listOf("method:save(Order)", "method:save(List, boolean)", "method:find"), model.ofKind(BlockKind.METHOD).map { it.id })
    }

    fun testOverloadsWithSameSimpleTypeNamesGetDistinctIds() {
        addClass("package a; public class Order {}")
        addClass("package b; public class Order {}")
        val model = supported("""
            package c;
            class Repo {
                void save(a.Order o) {}
                void save(b.Order o) {}
            }
        """.trimIndent())
        assertEquals(listOf("method:save(a.Order)", "method:save(b.Order)"), model.ofKind(BlockKind.METHOD).map { it.id })
        val cls = (myFixture.file as com.intellij.psi.PsiJavaFile).classes.single()
        val second = JavaBlockBuilder.findOwnMethod(cls, "save(b.Order)")
        assertSame(JavaBlockBuilder.ownMethods(cls)[1], second)
    }

    fun testKeywordsInTheClassTitle() {
        addClass("package a; public interface Named {}")
        addClass("package a; public class Base {}")
        assertEquals("class Foo extends Base implements Named", supported("package a; public class Foo extends Base implements Named {}").ofKind(BlockKind.CLASS).single().title)
        assertEquals("interface Foo", supported("package a; public interface Foo { void x(); }").ofKind(BlockKind.CLASS).single().title)
        assertEquals("@interface Foo", supported("package a; public @interface Foo {}").ofKind(BlockKind.CLASS).single().title)
    }

    fun testWhichFilesDontOpen() {
        assertEquals("Expected exactly one top-level class, interface, enum or record, found 2",
            (build("package a; class A {} class B {}") as BuildResult.Unsupported).reason)
        assertEquals("No class in this file", (build("package a;", "package-info.java") as BuildResult.Unsupported).reason)
    }

    fun testNestedClassMethodsStayInTheClassBlock() {
        val model = supported("""
            package a;
            public class Outer {
                static class Inner { void hidden() {} }
                void visible() { Runnable r = new Runnable() { public void run() {} }; }
            }
        """.trimIndent())
        assertEquals(listOf("method:visible"), model.ofKind(BlockKind.METHOD).map { it.id })
        assertTrue(visibleText(model.ofKind(BlockKind.CLASS).single()).contains("void hidden() {}"))
    }

    fun testEnumWithConstantBodiesAndRecordOpen() {
        val enumModel = supported("""
            package a;
            public enum Status {
                NEW { void label() {} },
                DONE;
                void shared() {}
            }
        """.trimIndent(), "Status.java")
        assertEquals(listOf("method:shared"), enumModel.ofKind(BlockKind.METHOD).map { it.id })
        assertEquals("enum Status", enumModel.ofKind(BlockKind.CLASS).single().title)
        val recordModel = supported("package a; public record Point(int x, int y) { int sum() { return x + y; } }", "Point.java")
        assertEquals("record Point", recordModel.ofKind(BlockKind.CLASS).single().title)
        assertEquals(listOf("method:sum"), recordModel.ofKind(BlockKind.METHOD).map { it.id })
    }

    fun testHalfTypedMethodDoesNotThrow() {
        val result = build("package a;\npublic class Foo {\n    void ok() {}\n    void broken(Strin\n    Unknown x;\n}\n")
        assertTrue(result is BuildResult.Supported)
    }

    fun testTheJavaSupportHandlesJavaFilesAndWritesJavaMethods() {
        val file = myFixture.configureByText("Foo.java", "package a; class Foo {}").virtualFile
        val java = LanguageSupports.forFile(file)
        assertTrue(java is JavaLanguageSupport)
        assertEquals("    public void go() {\n    }\n", java!!.newMethodText("go", "    ", inInterface = false))
        assertEquals("    void go();\n", java.newMethodText("go", "    ", inInterface = true))
        assertTrue(java.routes(project).isEmpty())
    }

    fun testAddMethodSearchListsJavaMethods() {
        addClass("package com.acme; public class Mailer { public void send(String to) {} void send(String to, int n) {} }")
        val refs = JavaMethodFinder.find(project).filter { it.classFqn == "com.acme.Mailer" }
        assertEquals(listOf("send(String)", "send(String, int)"), refs.map { it.method }.sorted())
        assertTrue(refs.any { it.label == "Mailer::send(String to)  com.acme" })
    }
}
