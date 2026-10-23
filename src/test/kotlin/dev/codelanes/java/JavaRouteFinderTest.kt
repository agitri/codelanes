package dev.codelanes.java

class JavaRouteFinderTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addClass("package org.springframework.web.bind.annotation; public @interface RestController {}")
        addClass("package org.springframework.web.bind.annotation; public @interface RequestMapping { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }")
        addClass("package org.springframework.web.bind.annotation; public enum RequestMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE }")
        for (verb in listOf("Get", "Post", "Put", "Delete", "Patch")) {
            addClass("package org.springframework.web.bind.annotation; public @interface ${verb}Mapping { String[] value() default {}; String[] path() default {}; }")
        }
    }

    private fun labels() = JavaRouteFinder.find(project).map { it.label }

    fun testVerbMappingsWithTheClassPrefix() {
        myFixture.addFileToProject(
            "com/acme/OrderController.java",
            """
            package com.acme;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/api")
            public class OrderController {
                @PostMapping("/orders")
                public void create() {}

                @GetMapping(path = "/orders/{id}")
                public void show(long id) {}

                @PutMapping("/orders/{id}") public void update() {}
                @DeleteMapping("/orders/{id}") public void delete() {}
                @PatchMapping("/orders/{id}") public void patch() {}

                public void helper() {}
            }
            """.trimIndent(),
        )
        // sorted by path, then label, as for PHP
        assertEquals(
            listOf(
                "POST /api/orders → OrderController::create",
                "DELETE /api/orders/{id} → OrderController::delete",
                "GET /api/orders/{id} → OrderController::show",
                "PATCH /api/orders/{id} → OrderController::patch",
                "PUT /api/orders/{id} → OrderController::update",
            ),
            labels(),
        )
        val create = JavaRouteFinder.find(project).first()
        assertTrue(create.filePath.endsWith("com/acme/OrderController.java"))
        assertEquals("create", create.methodName)
    }

    fun testRequestMappingWithMethodsSeveralPathsAndNoPath() {
        myFixture.addFileToProject(
            "com/acme/HealthController.java",
            """
            package com.acme;

            import org.springframework.web.bind.annotation.*;

            @RestController
            public class HealthController {
                @RequestMapping(value = {"/health", "/ping"}, method = {RequestMethod.GET, RequestMethod.HEAD})
                public String health() { return "ok"; }

                @RequestMapping("/any")
                public void any() {}

                @GetMapping
                public void root() {}
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "GET / → HealthController::root",
                "ANY /any → HealthController::any",
                "GET|HEAD /health → HealthController::health",
                "GET|HEAD /ping → HealthController::health",
            ),
            labels(),
        )
    }

    fun testConstantPathsAreEvaluatedAndOtherExpressionsShowAsQuestionMark() {
        addClass("package java.lang; public final class String {}") // no JDK here, and only String fields are constants
        myFixture.addFileToProject(
            "com/acme/Paths.java",
            "package com.acme; public class Paths { public static final String ORDERS = \"/orders\"; }",
        )
        myFixture.addFileToProject(
            "com/acme/OrderController.java",
            """
            package com.acme;

            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping(Paths.ORDERS)
            public class OrderController {
                @GetMapping(Paths.ORDERS + "/{id}")
                public void show() {}

                @GetMapping(path = Missing.PATH)
                public void broken() {}
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("GET /orders/orders/{id} → OrderController::show", "GET ? → OrderController::broken"),
            labels(),
        )
    }

    fun testAnOverloadedRouteCarriesItsMethodKey() {
        myFixture.addFileToProject(
            "com/acme/OrderController.java",
            """
            package com.acme;

            import org.springframework.web.bind.annotation.*;

            @RestController
            public class OrderController {
                @PostMapping("/orders")
                public void create(String body) {}

                public void create(int count) {}
            }
            """.trimIndent(),
        )
        assertEquals("create(String)", JavaRouteFinder.find(project).single().methodName)
    }

    fun testInterfacesAbstractClassesAndUnmappedClassesAreSkipped() {
        myFixture.addFileToProject(
            "com/acme/Api.java",
            """
            package com.acme;

            import org.springframework.web.bind.annotation.*;

            public interface Api {
                @GetMapping("/api") void get();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "com/acme/Base.java",
            """
            package com.acme;

            import org.springframework.web.bind.annotation.*;

            public abstract class Base {
                @GetMapping("/base") public void get() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("com/acme/Plain.java", "package com.acme; public class Plain { public void run() {} }")
        assertEquals(emptyList<String>(), labels())
    }
}
