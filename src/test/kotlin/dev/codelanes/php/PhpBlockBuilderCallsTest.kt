package dev.codelanes.php

import dev.codelanes.model.LinkKind

class PhpBlockBuilderCallsTest : PhpBuilderTestCase() {
    fun testInternalCallsBecomeLinks() {
        val model = supported(
            """
            <?php
            class Foo
            {
                public function bar(): void
                {
                    ${'$'}this->footest('x');
                    ${'$'}this->footest('y');
                    self::helper();
                    static::helper();
                }

                public function footest(string ${'$'}s): void {}

                private static function helper(): void {}
            }
            """.trimIndent() + "\n",
        )
        val calls = model.links.filter { it.kind == LinkKind.CALLS }.map { it.from to it.to }
        assertEquals(listOf("method:bar" to "method:footest", "method:bar" to "method:helper"), calls)
    }

    fun testCallsIgnoreOtherObjectsAndRecursion() {
        addPhp("src/Other.php", "<?php\nclass Other { public function bar(): void {} }\n")
        addPhp("src/Helps.php", "<?php\ntrait Helps { public function assist(): void {} }\n")
        val model = supported(
            """
            <?php
            class Foo
            {
                use Helps;

                public function __construct(private Other ${'$'}other)
                {
                    ${'$'}this->bar();
                }

                public function bar(): void
                {
                    ${'$'}this->other->bar();
                    ${'$'}this->bar();
                    ${'$'}this->assist();
                }
            }
            """.trimIndent() + "\n",
        )
        assertTrue(model.links.none { it.kind == LinkKind.CALLS })
    }
}
