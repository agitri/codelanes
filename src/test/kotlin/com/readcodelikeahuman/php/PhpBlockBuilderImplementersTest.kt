package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderImplementersTest : PhpBuilderTestCase() {
    private val renderable = "<?php\nnamespace App;\n\ninterface Renderable\n{\n    public function render(): string;\n}\n"

    fun testInterfaceShowsItsImplementersAndOnlyTheirImplementingMethods() {
        addPhp("src/Order.php", "<?php\nnamespace App;\nclass Order implements Renderable\n{\n    public function total(): int { return 1; }\n    public function render(): string { return ''; }\n}\n")
        addPhp("src/Invoice.php", "<?php\nnamespace App;\nclass Invoice implements Renderable\n{\n    public function render(): string { return 'i'; }\n}\n")
        val model = supported(renderable)
        val central = "class:\\App\\Renderable"

        assertEquals(listOf("implementer:\\App\\Invoice", "implementer:\\App\\Order"), model.ofKind(BlockKind.IMPLEMENTER).map { it.id })
        assertEquals("class Order", model.block("implementer:\\App\\Order").title)
        assertTrue(model.ofKind(BlockKind.IMPLEMENTER).all { it.collapsed })

        assertEquals(
            listOf("implementation:\\App\\Invoice::render", "implementation:\\App\\Order::render"),
            model.ofKind(BlockKind.IMPLEMENTATION).map { it.id },
        )
        val orderRender = model.block("implementation:\\App\\Order::render")
        assertEquals("Order::render()", orderRender.title)
        assertTrue(orderRender.filePath.endsWith("src/Order.php"))

        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, central, "implementer:\\App\\Order")))
        assertTrue(model.links.contains(Link(LinkKind.OWNS, "implementer:\\App\\Order", "implementation:\\App\\Order::render")))
        assertTrue(model.links.any { it.kind == LinkKind.OVERRIDES && it.from == "implementation:\\App\\Order::render" && it.to == "method:render" })
    }

    fun testTraitShowsTheClassesThatUseIt() {
        addPhp("src/Order.php", "<?php\nnamespace App;\nclass Order\n{\n    use Stamps;\n    public function touch(): void {}\n}\n")
        val model = supported("<?php\nnamespace App;\n\ntrait Stamps\n{\n    public function touch(): void {}\n}\n")
        assertEquals(listOf("implementer:\\App\\Order"), model.ofKind(BlockKind.IMPLEMENTER).map { it.id })
        assertEquals(listOf("implementation:\\App\\Order::touch"), model.ofKind(BlockKind.IMPLEMENTATION).map { it.id })
    }

    fun testAbstractClassShowsItsSubclasses() {
        addPhp("src/Order.php", "<?php\nnamespace App;\nclass Order extends Model\n{\n    protected function validate(): bool { return true; }\n}\n")
        val model = supported("<?php\nnamespace App;\n\nabstract class Model\n{\n    abstract protected function validate(): bool;\n}\n")
        assertEquals(listOf("implementer:\\App\\Order"), model.ofKind(BlockKind.IMPLEMENTER).map { it.id })
        assertEquals(listOf("implementation:\\App\\Order::validate"), model.ofKind(BlockKind.IMPLEMENTATION).map { it.id })
    }

    fun testConcreteClassHasNoImplementersLane() {
        addPhp("src/Child.php", "<?php\nnamespace App;\nclass Child extends Base {}\n")
        val model = supported("<?php\nnamespace App;\n\nclass Base\n{\n}\n")
        assertTrue(model.ofKind(BlockKind.IMPLEMENTER).isEmpty())
    }

    fun testAtMostTenImplementersPlusAMoreBlock() {
        (1..12).forEach { n -> addPhp("src/R$n.php", "<?php\nnamespace App;\nclass R${n.toString().padStart(2, '0')} implements Renderable { public function render(): string { return ''; } }\n") }
        val model = supported(renderable)
        assertEquals(10, model.ofKind(BlockKind.IMPLEMENTER).size)
        assertEquals("implementer:\\App\\R01", model.ofKind(BlockKind.IMPLEMENTER).first().id)
        val more = model.ofKind(BlockKind.MORE).single()
        assertEquals("and 2 more", more.title)
        assertEquals(listOf("R11", "R12"), more.summary)
    }
}
