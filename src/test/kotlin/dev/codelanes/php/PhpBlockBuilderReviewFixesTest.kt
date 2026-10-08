package dev.codelanes.php

import dev.codelanes.model.BlockKind
import dev.codelanes.model.BuildResult

class PhpBlockBuilderReviewFixesTest : PhpBuilderTestCase() {
    private val codeBefore = BuildResult.Unsupported("File contains code before the class")

    fun testStatementsBeforeClassAreUnsupported() {
        assertEquals(codeBefore, build("<?php\n\$x = 1;\nclass A {}\n"))
        assertEquals(codeBefore, build("<?php\necho 'hi';\nclass A {}\n"))
        assertEquals(codeBefore, build("<?php\nrequire_once 'boot.php';\nclass A {}\n"))
        assertEquals(codeBefore, build("<?php\nnamespace N;\nconst X = 1;\nclass A {}\n"))
        assertEquals(codeBefore, build("<?php\nreturn;\nclass A {}\n"))
        assertEquals(codeBefore, build("<html>\n<?php\nclass A {}\n"))
    }

    fun testDeclareNamespaceUseAndCommentsBeforeClassAreSupported() {
        val model = supported(
            "<?php\n// file comment\ndeclare(strict_types=1);\n\nnamespace App;\n\nuse App\\X;\n\n/** Doc */\n#[\\Attribute]\nclass A {}\n",
        )
        assertEquals("namespace App · 1 imports", model.block("header").title)
    }

    fun testDuplicateMethodNamesAreUnsupportedNotACrash() {
        assertEquals(
            BuildResult.Unsupported("Duplicate method name foo"),
            build("<?php\nclass A {\n    public function foo() {}\n    public function FOO() {}\n}\n"),
        )
    }

    fun testConstructorTakingOwnClassIsNotADependency() {
        val model = supported("<?php\nclass Node {\n    public function __construct(private ?self \$parent, private ?Node \$next) {}\n}\n")
        assertTrue(model.ofKind(BlockKind.DEPENDENCY).isEmpty())
    }
}
