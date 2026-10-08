package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderStructureTest : PhpBuilderTestCase() {
    private val foo = """
        <?php
        namespace App\Service;

        use App\Contract\Barro;

        class Foo implements Barro
        {
            private const MAX = 3;
            private string ${'$'}name;

            public function __construct(string ${'$'}name)
            {
                ${'$'}this->name = ${'$'}name;
            }

            public function bar(): void
            {
            }

            public function footest(string ${'$'}s = 'x'): string
            {
                return ${'$'}s;
            }
        }
    """.trimIndent() + "\n"

    fun testNonPhpFileIsUnsupported() {
        assertInstanceOf(build("hello", "notes.txt"), BuildResult.Unsupported::class.java)
    }

    fun testProceduralFileIsUnsupported() {
        val result = build("<?php\necho 'hi';\n")
        assertEquals(BuildResult.Unsupported("Expected exactly one class, interface, trait or enum, found 0"), result)
    }

    fun testTwoClassesAreUnsupported() {
        val result = build("<?php\nclass A {}\nclass B {}\n")
        assertEquals(BuildResult.Unsupported("Expected exactly one class, interface, trait or enum, found 2"), result)
    }

    fun testTopLevelFunctionIsUnsupported() {
        val result = build("<?php\nfunction helper() {}\nclass A {}\n")
        assertEquals(BuildResult.Unsupported("File contains top-level functions"), result)
    }

    fun testCodeAfterClassIsUnsupported() {
        val result = build("<?php\nclass A {}\necho 'loose';\n")
        assertEquals(BuildResult.Unsupported("File contains code after the class"), result)
    }

    fun testAnonymousClassInsideMethodIsStillSupported() {
        val model = supported("<?php\nclass A {\n    public function make() { return new class {}; }\n}\n")
        assertEquals(listOf("method:make"), model.ofKind(BlockKind.METHOD).map { it.id })
    }

    fun testHeaderClassAndMethodBlocks() {
        val model = supported(foo)

        val header = model.block("header")
        assertEquals("namespace App\\Service · 1 imports", header.title)
        assertTrue(header.collapsed)
        assertTrue(visibleText(header).contains("use App\\Contract\\Barro;"))

        val cls = model.block("class:\\App\\Service\\Foo")
        assertEquals(BlockKind.CLASS, cls.kind)
        assertEquals("class Foo implements Barro", cls.title)
        assertFalse(cls.collapsed)
        val classText = visibleText(cls)
        assertTrue(classText.contains("private const MAX = 3;"))
        assertTrue(classText.contains("public function __construct"))
        assertFalse(classText.contains("function bar"))
        assertFalse(classText.contains("function footest"))

        val methods = model.ofKind(BlockKind.METHOD)
        assertEquals(listOf("method:bar", "method:footest"), methods.map { it.id })
        assertEquals(listOf("bar()", "footest(string \$s)"), methods.map { it.title })
        assertTrue(visibleText(methods[1]).startsWith("public function footest"))

        val owns = model.links.filter { it.kind == LinkKind.OWNS }.map { it.from to it.to }
        assertEquals(
            listOf(cls.id to "method:bar", cls.id to "method:footest"),
            owns,
        )
    }

    fun testDocCommentAndAttributeBelongToMethodBlock() {
        val model = supported(
            """
            <?php
            class A
            {
                /** Does the thing. */
                #[\Deprecated]
                public function thing(): void {}
            }
            """.trimIndent() + "\n",
        )
        val method = visibleText(model.block("method:thing"))
        assertTrue(method, method.startsWith("/** Does the thing. */"))
        assertTrue(method, method.contains("#[\\Deprecated]"))
        assertFalse(visibleText(model.ofKind(BlockKind.CLASS).single()).contains("Does the thing"))
    }

    fun testEveryNonWhitespaceCharIsInExactlyOneBlock() {
        val model = supported(foo)
        val text = myFixture.file.text
        val counts = IntArray(text.length)
        model.blocks.filter { it.filePath == myFixture.file.virtualFile.path }.forEach { block ->
            (block.range.start until block.range.end)
                .filter { offset -> block.excluded.none { it.contains(offset) } }
                .forEach { counts[it]++ }
        }
        text.indices.filter { !text[it].isWhitespace() }.forEach {
            assertEquals("offset $it '${text[it]}'", 1, counts[it])
        }
    }

    fun testInterfaceFileUsesSameLayout() {
        val model = supported("<?php\ninterface Barro\n{\n    public function bar(): void;\n}\n")
        assertEquals("interface Barro", model.ofKind(BlockKind.CLASS).single().title)
        assertEquals(listOf("bar()"), model.ofKind(BlockKind.METHOD).map { it.title })
    }

    fun testEnumCasesStayInCenterBlock() {
        val model = supported(
            "<?php\nenum Suit: string\n{\n    case Hearts = 'H';\n    public function label(): string { return 'x'; }\n}\n",
        )
        val center = model.ofKind(BlockKind.CLASS).single()
        assertEquals("enum Suit", center.title)
        assertTrue(visibleText(center).contains("case Hearts = 'H';"))
        assertEquals(listOf("method:label"), model.ofKind(BlockKind.METHOD).map { it.id })
    }
}
