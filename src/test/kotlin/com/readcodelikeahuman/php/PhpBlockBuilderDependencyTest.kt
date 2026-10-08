package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderDependencyTest : PhpBuilderTestCase() {
    fun testConstructorTypedParametersBecomeDependencies() {
        addPhp("src/UserRepo.php", "<?php\nnamespace App;\nclass UserRepo { const LIMIT = 10; }\n")
        addPhp("src/Clock.php", "<?php\nnamespace App;\ninterface Clock {}\n")

        val model = supported(
            """
            <?php
            namespace App;

            class Foo
            {
                public function __construct(
                    private UserRepo ${'$'}repo,
                    private ?Clock ${'$'}clock,
                    private UserRepo ${'$'}sameRepoAgain,
                    private string ${'$'}name,
                    private int ${'$'}limit = UserRepo::LIMIT,
                ) {}
            }
            """.trimIndent() + "\n",
        )

        val deps = model.ofKind(BlockKind.DEPENDENCY)
        assertEquals(listOf("dependency:\\App\\UserRepo", "dependency:\\App\\Clock"), deps.map { it.id })
        assertEquals(listOf("class UserRepo", "interface Clock"), deps.map { it.title })
        assertTrue(deps.all { it.collapsed })
        assertTrue(model.links.contains(Link(LinkKind.INJECTS, "dependency:\\App\\UserRepo", "class:\\App\\Foo")))
    }

    fun testUnresolvedDependencyGetsNoBlock() {
        val model = supported("<?php\nclass Foo { public function __construct(private Missing ${'$'}m) {} }\n")
        assertTrue(model.ofKind(BlockKind.DEPENDENCY).isEmpty())
    }

    fun testNoConstructorMeansNoDependencies() {
        val model = supported("<?php\nclass Foo { public function bar() {} }\n")
        assertTrue(model.ofKind(BlockKind.DEPENDENCY).isEmpty())
    }
}
