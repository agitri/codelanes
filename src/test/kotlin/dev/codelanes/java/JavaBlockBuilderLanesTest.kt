package dev.codelanes.java

import dev.codelanes.model.BlockKind
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class JavaBlockBuilderLanesTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addSpringStubs()
        addClass("package com.acme; public interface Repo<T> { void save(T item); }")
        addClass("package com.acme; public interface Clock { long now(); }")
        addClass("package com.acme; public interface Mailer { void send(); }")
        addClass("package com.acme; public abstract class BaseService { public abstract void place(); }")
        addClass("package com.acme; public interface Audited extends Clock {}")
    }

    fun testParentAndInterfaceLanes() {
        val model = supported("""
            package com.acme;
            public class OrderService extends BaseService implements Audited {
                public void place() {}
                public long now() { return 0; }
            }
        """.trimIndent())
        val cls = "class:com.acme.OrderService"
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:com.acme.BaseService", cls)))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTS, "interface:com.acme.Audited", cls)))
        assertEquals(listOf("place()"), model.block("parent:com.acme.BaseService").summary)
        assertTrue(model.links.any { it.kind == LinkKind.OVERRIDES && it.from == "method:place" && it.to == "parent:com.acme.BaseService" })
    }

    fun testAnInterfaceExtendingAnotherShowsItInTheInterfaceLane() {
        val model = supported("package com.acme; public interface Timed extends Clock { }", "Timed.java")
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "interface:com.acme.Clock", "class:com.acme.Timed")))
    }

    fun testAHalfTypedInterfaceNamingATypeTwiceGetsOneBlock() {
        val model = supported("package com.acme; public interface Timed extends Clock implements Clock { }", "Timed.java")
        assertEquals(listOf("interface:com.acme.Clock"), model.ofKind(BlockKind.INTERFACE).map { it.id })
    }

    fun testPlusParentsDigsDeeper() {
        val model = supported("package com.acme; public class Svc implements Audited { public long now() { return 0; } }", "Svc.java",
            revealed = setOf("interface:com.acme.Audited"))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "interface:com.acme.Clock", "interface:com.acme.Audited")))
    }

    fun testConstructorAndAutowiredFieldsAreInjected() {
        val model = supported("""
            package com.acme;
            import org.springframework.beans.factory.annotation.Autowired;
            public class OrderService {
                @Autowired private Mailer mailer;
                private Clock notInjected;
                public OrderService(Clock clock, int retries, String name) {}
            }
        """.trimIndent())
        val deps = model.ofKind(BlockKind.DEPENDENCY).map { it.id }.toSet()
        assertEquals(setOf("dependency:com.acme.Clock", "dependency:com.acme.Mailer"), deps)
        assertTrue(model.links.contains(Link(LinkKind.INJECTS, "dependency:com.acme.Mailer", "class:com.acme.OrderService")))
    }

    fun testLombokRequiredArgsConstructorInjectsFinalFields() {
        val model = supported("""
            package com.acme;
            import lombok.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class OrderService {
                private final Mailer mailer;
                private Clock notFinal;
            }
        """.trimIndent())
        assertEquals(listOf("dependency:com.acme.Mailer"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testLombokSkipsStaticAndInitializedFields() {
        val model = supported("""
            package com.acme;
            @lombok.RequiredArgsConstructor
            public class OrderService {
                private static final Mailer DEFAULT = null;
                private final Clock clock = null;
                private final Repo<String> repo;
            }
        """.trimIndent())
        assertEquals(listOf("dependency:com.acme.Repo"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testGenericProjectTypeIsADependency() {
        val model = supported("package com.acme; public class S { public S(Repo<String> repo) {} }", "S.java")
        assertEquals(listOf("dependency:com.acme.Repo"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testJdkTypesAreNotDependencies() {
        addClass("package java.util; public interface List<E> {}")
        addClass("package javax.inject; public interface Provider<T> { T get(); }")
        val model = supported("package com.acme; public class S { public S(java.util.List<Clock> all, javax.inject.Provider<Clock> p) {} }", "S.java")
        assertEquals(listOf("dependency:javax.inject.Provider"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testAnUnresolvedLombokAnnotationStillCounts() {
        // No lombok.AllArgsConstructor import and no such class with this name in the default package: matched by short name.
        val model = supported("package com.acme; @AllArgsConstructor public class S { private Mailer m; }", "S.java")
        assertEquals(listOf("dependency:com.acme.Mailer"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testALibraryParentWithoutSourceIsSourceless() {
        val model = supported("package com.acme; public class MyTest extends junit.framework.TestCase { }", "MyTest.java")
        val parent = model.block("parent:junit.framework.TestCase")
        assertTrue(parent.sourceless)
        assertTrue(parent.collapsed)
        assertTrue(parent.summary.isNotEmpty())
    }

    fun testOverridesLandOnTheOverriddenMethod() {
        val model = supported("""
            package com.acme;
            public class OrderService extends BaseService { public void place() {} }
        """.trimIndent())
        val link = model.links.single { it.kind == LinkKind.OVERRIDES && it.from == "method:place" && it.to == "parent:com.acme.BaseService" }
        val baseClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
            .findClass("com.acme.BaseService", com.intellij.psi.search.GlobalSearchScope.allScope(project))!!
        val place = baseClass.findMethodsByName("place", false).single()
        assertEquals(baseClass.containingFile.virtualFile.path, model.block("parent:com.acme.BaseService").filePath)
        assertNotNull(link.targetRange)
        assertEquals(JavaBlockBuilder.rangeOf(place), link.targetRange)

        val sourceless = supported("public class MyTest extends junit.framework.TestCase { protected void setUp() {} }", "MyTest.java")
        val junit = sourceless.links.single { it.kind == LinkKind.OVERRIDES && it.from == "method:setUp" && it.to == "parent:junit.framework.TestCase" }
        assertNull(junit.targetRange)
    }
}
