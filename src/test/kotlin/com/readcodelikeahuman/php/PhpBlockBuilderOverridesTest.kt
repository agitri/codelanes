package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderOverridesTest : PhpBuilderTestCase() {
    fun testMethodsLinkToTheInterfaceAndParentMethodsTheyImplement() {
        addPhp("src/Barro.php", "<?php\nnamespace App;\ninterface Barro { public function bar(): void; }\n")
        addPhp("src/Base.php", "<?php\nnamespace App;\nabstract class Base { abstract public function id(): int; }\n")
        val model = supported(
            "<?php\nnamespace App;\nclass Foo extends Base implements Barro\n{\n" +
                "    public function bar(): void {}\n    public function id(): int { return 1; }\n    public function own(): void {}\n}\n",
        )
        val overrides = model.links.filter { it.kind == LinkKind.OVERRIDES }
        assertEquals(
            setOf(
                Link(LinkKind.OVERRIDES, "method:bar", "interface:\\App\\Barro"),
                Link(LinkKind.OVERRIDES, "method:id", "parent:\\App\\Base"),
            ),
            overrides.toSet(),
        )
    }
}
