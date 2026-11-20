package dev.codelanes.kotlin

class KotlinRouteFinderTest : KotlinTestCase() {
    override fun setUp() {
        super.setUp()
        addFile("org/springframework/web/bind/annotation/Ann.java", "package org.springframework.web.bind.annotation; public @interface RestController {}")
        addFile("org/springframework/web/bind/annotation/RequestMapping.java", "package org.springframework.web.bind.annotation; public @interface RequestMapping { String[] value() default {}; String[] path() default {}; }")
        for (verb in listOf("Get", "Post")) {
            addFile("org/springframework/web/bind/annotation/${verb}Mapping.java", "package org.springframework.web.bind.annotation; public @interface ${verb}Mapping { String[] value() default {}; String[] path() default {}; }")
        }
    }

    fun testRoutesOfAKotlinController() {
        addFile(
            "acme/NoteController.kt",
            """
            package acme

            import org.springframework.web.bind.annotation.*

            @RestController
            @RequestMapping("/notes")
            class NoteController {
                @GetMapping("/{id}")
                fun get(id: Long): String = ""

                @PostMapping
                fun create(): String = ""

                fun helper() {}
            }

            @RequestMapping("/x")
            interface Api {
                @GetMapping("/y")
                fun y()
            }
            """,
        )
        val routes = inBackground { KotlinRouteFinder.find(project) }
        assertEquals(
            listOf("POST /notes → NoteController::create", "GET /notes/{id} → NoteController::get"),
            routes.map { it.label },
        )
        assertTrue(routes.all { it.filePath.endsWith("acme/NoteController.kt") })
        assertEquals(listOf("NoteController.create", "NoteController.get"), routes.map { it.methodName })
    }
}
