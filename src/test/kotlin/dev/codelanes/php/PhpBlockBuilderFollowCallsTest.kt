package dev.codelanes.php

import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class PhpBlockBuilderFollowCallsTest : PhpBuilderTestCase() {
    private val order = """
        <?php
        namespace App;

        class Order
        {
            public function __construct(private UserRepository ${'$'}users, private Clock ${'$'}clock) {}

            public function render(): string
            {
                return ${'$'}this->users->findName(1) . ${'$'}this->clock->now() . ${'$'}this->total();
            }

            public function total(): int { return 1; }
        }

    """.trimIndent()

    private fun services() {
        addPhp("src/UserRepository.php", "<?php\nnamespace App;\nclass UserRepository\n{\n    public function findName(int \$id): string { return \$this->format(\$id); }\n    private function format(int \$id): string { return 'u' . \$id; }\n}\n")
        addPhp("src/Clock.php", "<?php\nnamespace App;\ninterface Clock\n{\n    public function now(): string;\n}\n")
    }

    private fun build(followed: Set<String>): BlockModel {
        val file = myFixture.configureByText("Order.php", order)
        return (PhpBlockBuilder.build(file, followedCalls = followed) as BuildResult.Supported).model
    }

    fun testFollowingAMethodShowsTheMethodsItCallsInOtherClasses() {
        services()
        val model = build(setOf("method:render"))
        val callees = model.ofKind(BlockKind.CALLEE)
        assertEquals(setOf("callee:\\App\\UserRepository::findName", "callee:\\App\\Clock::now"), callees.map { it.id }.toSet())
        assertEquals("UserRepository::findName(int \$id)", model.block("callee:\\App\\UserRepository::findName").title)
        assertTrue(model.block("callee:\\App\\UserRepository::findName").filePath.endsWith("src/UserRepository.php"))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:render", "callee:\\App\\UserRepository::findName")))
        // calls inside the same class stay ordinary method-to-method calls
        assertFalse(callees.any { it.id.endsWith("::total") })
    }

    fun testNothingIsFollowedUnlessAsked() {
        services()
        assertTrue(build(emptySet()).ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testFollowingACalleeGoesOneLevelDeeper() {
        services()
        val model = build(setOf("method:render", "callee:\\App\\UserRepository::findName"))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "callee:\\App\\UserRepository::findName", "callee:\\App\\UserRepository::format")))
    }

    fun testACallToAnInterfaceMethodAlsoShowsItsImplementations() {
        services()
        addPhp("src/SystemClock.php", "<?php\nnamespace App;\nclass SystemClock implements Clock\n{\n    public function now(): string { return 'now'; }\n}\n")
        val model = build(setOf("method:render"))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "callee:\\App\\Clock::now", "callee:\\App\\SystemClock::now")))
        assertEquals("SystemClock::now()", model.block("callee:\\App\\SystemClock::now").title)
    }
}
