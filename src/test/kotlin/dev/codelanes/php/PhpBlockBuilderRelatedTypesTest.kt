package dev.codelanes.php

import dev.codelanes.model.BlockKind
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class PhpBlockBuilderRelatedTypesTest : PhpBuilderTestCase() {
    fun testInterfacesParentAndTraitsBecomeCollapsedBlocks() {
        addPhp("src/Barro.php", "<?php\nnamespace App;\ninterface Barro { public function bar(): void; }\n")
        addPhp("src/Base.php", "<?php\nnamespace App;\nabstract class Base {}\n")
        addPhp("src/Loggable.php", "<?php\nnamespace App;\ntrait Loggable {}\n")

        val model = supported(
            """
            <?php
            namespace App;

            class Foo extends Base implements Barro
            {
                use Loggable;

                public function bar(): void {}
            }
            """.trimIndent() + "\n",
        )
        val cls = "class:\\App\\Foo"

        val iface = model.block("interface:\\App\\Barro")
        assertEquals(BlockKind.INTERFACE, iface.kind)
        assertEquals("interface Barro", iface.title)
        assertTrue(iface.collapsed)
        assertTrue(iface.filePath.endsWith("src/Barro.php"))

        assertEquals("class Base", model.block("parent:\\App\\Base").title)
        assertEquals("trait Loggable", model.block("trait:\\App\\Loggable").title)

        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTS, "interface:\\App\\Barro", cls)))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:\\App\\Base", cls)))
        assertTrue(model.links.contains(Link(LinkKind.USES, "trait:\\App\\Loggable", cls)))
    }

    fun testInterfaceExtendingInterfaces() {
        addPhp("src/Countable2.php", "<?php\nnamespace App;\ninterface Countable2 {}\n")
        val model = supported("<?php\nnamespace App;\ninterface Barro extends Countable2 {}\n")
        val parent = model.block("interface:\\App\\Countable2")
        assertEquals(BlockKind.INTERFACE, parent.kind)
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, parent.id, "class:\\App\\Barro")))
    }

    fun testUnresolvedInterfaceGetsNoBlock() {
        val model = supported("<?php\nnamespace App;\nclass Foo implements DoesNotExist {}\n")
        assertTrue(model.ofKind(BlockKind.INTERFACE).isEmpty())
        assertEquals("class Foo implements DoesNotExist", model.ofKind(BlockKind.CLASS).single().title)
    }

    private fun chain() {
        addPhp("src/Entity.php", "<?php\nnamespace App;\nabstract class Entity {}\n")
        addPhp("src/Identifiable.php", "<?php\nnamespace App;\ninterface Identifiable {}\n")
        addPhp("src/Model.php", "<?php\nnamespace App;\nabstract class Model extends Entity implements Identifiable {}\n")
    }

    fun testRevealedParentShowsItsOwnParentsAndInterfaces() {
        chain()
        val file = myFixture.configureByText("Order.php", "<?php\nnamespace App;\nclass Order extends Model {}\n")
        val model = (PhpBlockBuilder.build(file, revealed = setOf("parent:\\App\\Model")) as dev.codelanes.model.BuildResult.Supported).model
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:\\App\\Entity", "parent:\\App\\Model")))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTS, "interface:\\App\\Identifiable", "parent:\\App\\Model")))
    }

    fun testWithoutRevealOnlyDirectParentsShow() {
        chain()
        val model = supported("<?php\nnamespace App;\nclass Order extends Model {}\n")
        assertFalse(model.blocks.any { it.id == "parent:\\App\\Entity" })
    }
}
