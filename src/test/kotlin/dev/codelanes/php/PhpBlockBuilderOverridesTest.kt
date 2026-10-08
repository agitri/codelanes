package dev.codelanes.php

import dev.codelanes.model.LinkKind

class PhpBlockBuilderOverridesTest : PhpBuilderTestCase() {
    fun testMethodsLinkToTheInterfaceAndParentMethodsTheyImplement() {
        addPhp("src/Barro.php", "<?php\nnamespace App;\ninterface Barro { public function bar(): void; }\n")
        addPhp("src/Base.php", "<?php\nnamespace App;\nabstract class Base { abstract public function id(): int; }\n")
        val model = supported(
            "<?php\nnamespace App;\nclass Foo extends Base implements Barro\n{\n" +
                "    public function bar(): void {}\n    public function id(): int { return 1; }\n    public function own(): void {}\n}\n",
        )
        val overrides = model.links.filter { it.kind == LinkKind.OVERRIDES }.map { it.from to it.to }
        assertEquals(setOf("method:bar" to "interface:\\App\\Barro", "method:id" to "parent:\\App\\Base"), overrides.toSet())
    }

    fun testOverrideLinksPointAtTheOverriddenMethodInsideTheParent() {
        val base = myFixture.addFileToProject("src/Base.php", "<?php\nnamespace App;\nabstract class Base\n{\n    protected int ${'$'}x = 0;\n\n    abstract public function id(): int;\n}\n")
        val model = supported("<?php\nnamespace App;\nclass Foo extends Base\n{\n    public function id(): int { return 1; }\n}\n")
        val link = model.links.single { it.kind == LinkKind.OVERRIDES }
        val range = link.targetRange!!
        assertEquals("abstract public function id(): int;", base.text.substring(range.start, range.end))
    }
}
