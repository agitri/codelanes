package dev.codelanes.php

import dev.codelanes.model.BlockKind

class PhpBlockBuilderSummaryTest : PhpBuilderTestCase() {
    fun testOtherFileBlocksListTheirMethodSignatures() {
        addPhp(
            "src/Barro.php",
            "<?php\nnamespace App;\ninterface Barro\n{\n    public function bar(): void;\n    public function footest(string \$s): string;\n}\n",
        )
        val model = supported("<?php\nnamespace App;\nclass Foo implements Barro\n{\n    public function bar(): void {}\n    public function footest(string \$s): string { return \$s; }\n}\n")
        assertEquals(listOf("bar()", "footest(string \$s)"), model.block("interface:\\App\\Barro").summary)
    }

    fun testOwnBlocksHaveNoSummary() {
        val model = supported("<?php\nclass Foo\n{\n    public function bar(): void {}\n}\n")
        assertTrue(model.blocks.filter { it.kind != BlockKind.INTERFACE }.all { it.summary.isEmpty() })
    }
}
