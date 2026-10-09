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

    fun testAMethodAddedFromSearchBecomesABlock() {
        services()
        val file = myFixture.configureByText("Order.php", order)
        val model = (PhpBlockBuilder.build(file, added = setOf("\\App\\UserRepository::findName")) as BuildResult.Supported).model
        val block = model.block("callee:\\App\\UserRepository::findName")
        assertEquals(BlockKind.CALLEE, block.kind)
        assertEquals("UserRepository::findName(int \$id)", block.title)
    }

    fun testADecoratorCallingTheInnerServiceFollowsThroughTheInterface() {
        addPhp("src/Repo.php", "<?php\nnamespace App;\ninterface Repo { public function find(): string; }\n")
        addPhp("src/DbRepo.php", "<?php\nnamespace App;\nclass DbRepo implements Repo { public function find(): string { return 'db'; } }\n")
        addPhp("src/CachedRepo.php", "<?php\nnamespace App;\nclass CachedRepo implements Repo\n{\n    public function __construct(private Repo \$inner) {}\n    public function find(): string { return \$this->inner->find(); }\n}\n")
        val file = myFixture.configureByText("Ctl.php", "<?php\nnamespace App;\nclass Ctl\n{\n    public function __construct(private CachedRepo \$repo) {}\n    public function go(): string { return \$this->repo->find(); }\n}\n")
        val model = (PhpBlockBuilder.build(file, followedCalls = setOf("method:go", "callee:\\App\\CachedRepo::find")) as BuildResult.Supported).model
        assertTrue(model.links.toString(), model.links.contains(Link(LinkKind.CALLS_INTO, "callee:\\App\\CachedRepo::find", "callee:\\App\\Repo::find")))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "callee:\\App\\Repo::find", "callee:\\App\\DbRepo::find")))
    }

    fun testAFollowedChainIsCappedWithAMoreBlock() {
        val services = (1..10).map { i -> "S$i" }
        services.forEach { s ->
            val calls = (1..10).joinToString("") { j -> "\$this->t$j->run(); " }
            val props = (1..10).joinToString(", ") { j -> "private T${s}x$j \$t$j" }
            addPhp("src/$s.php", "<?php\nnamespace App;\nclass $s\n{\n    public function __construct($props) {}\n    public function run(): void { $calls }\n}\n")
            (1..10).forEach { j -> addPhp("src/T${s}x$j.php", "<?php\nnamespace App;\nclass T${s}x$j { public function run(): void {} }\n") }
        }
        val ctlProps = services.joinToString(", ") { "private $it \$${it.lowercase()}" }
        val ctlCalls = services.joinToString(" ") { "\$this->${it.lowercase()}->run();" }
        val file = myFixture.configureByText("Big.php", "<?php\nnamespace App;\nclass Big\n{\n    public function __construct($ctlProps) {}\n    public function go(): void { $ctlCalls }\n}\n")
        val followed = setOf("method:go") + services.map { "callee:\\App\\$it::run" }
        val model = (PhpBlockBuilder.build(file, followedCalls = followed) as BuildResult.Supported).model
        assertTrue(model.ofKind(BlockKind.CALLEE).size <= PhpBlockBuilder.MAX_CHAIN_BLOCKS)
        assertTrue(model.ofKind(BlockKind.MORE).any { it.title.startsWith("chain cut off") })
    }
}
