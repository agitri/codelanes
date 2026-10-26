package dev.codelanes.python

import com.jetbrains.python.psi.PyFile
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class PythonBlockBuilderStructureTest : PythonTestCase() {
    private val views = """
        ${"\"\"\""}Order endpoints.${"\"\"\""}
        import os
        from shop.orders.service import OrderService

        router = make_router()

        def create_order(payload):
            return OrderService().place(payload)

        class OrderView(Base):
            ${"\"\"\""}A view.${"\"\"\""}
            template = "x.html"

            def get(self, request):
                return render(request)

            @staticmethod
            async def post(request, *, dry_run: bool = False):
                pass
    """

    private fun model(path: String, text: String): BlockModel {
        addModule("shop/__init__.py", "")
        if (path != "shop/orders/__init__.py") addModule("shop/orders/__init__.py", "")
        val file = open(path, text) as PyFile
        return when (val result = PythonBlockBuilder.build(file, emptySet(), emptySet(), emptySet())) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }
    }

    /** The text of [block] as shown on the canvas: its range minus its excluded ranges. */
    private fun visibleText(block: Block): String {
        val text = myFixture.file.text
        return (block.range.start until block.range.end)
            .filter { offset -> block.excluded.none { it.contains(offset) } }
            .map { text[it] }
            .joinToString("")
    }

    fun testAModuleBecomesHeaderModuleClassAndFunctionBlocks() {
        val model = model("shop/orders/views.py", views)
        assertSameElements(
            model.blocks.map { it.id },
            "header", "module:shop.orders.views", "method:create_order", "class:shop.orders.views.OrderView",
            "method:OrderView.get", "method:OrderView.post",
        )
        assertEquals(BlockKind.MODULE, model.block("module:shop.orders.views").kind)
        assertEquals(BlockKind.CLASS, model.block("class:shop.orders.views.OrderView").kind)
        assertEquals(BlockKind.METHOD, model.block("method:create_order").kind)
    }

    fun testTitles() {
        val model = model("shop/orders/views.py", views)
        assertEquals("module shop.orders.views", model.block("module:shop.orders.views").title)
        assertEquals("2 imports", model.block("header").title)
        assertTrue(model.block("header").collapsed)
        assertEquals("class OrderView(Base)", model.block("class:shop.orders.views.OrderView").title)
        assertEquals("def create_order(payload)", model.block("method:create_order").title)
        assertEquals("def get(self, request)", model.block("method:OrderView.get").title)
        assertEquals("async def post(request, *, dry_run: bool = False)", model.block("method:OrderView.post").title)
    }

    fun testTheModuleBlockShowsOnlyModuleStatements() {
        val model = model("shop/orders/views.py", views)
        val text = visibleText(model.block("module:shop.orders.views"))
        assertTrue(text.contains("\"\"\"Order endpoints.\"\"\""))
        assertTrue(text.contains("router = make_router()"))
        assertFalse(text.contains("def create_order"))
        assertFalse(text.contains("class OrderView"))
        assertFalse(text.contains("import os"))
        assertTrue(visibleText(model.block("header")).contains("from shop.orders.service import OrderService"))
    }

    fun testTheClassBlockShowsItsBodyButNotItsMethods() {
        val model = model("shop/orders/views.py", views)
        val text = visibleText(model.block("class:shop.orders.views.OrderView"))
        assertTrue(text.startsWith("class OrderView(Base):"))
        assertTrue(text.contains("\"\"\"A view.\"\"\""))
        assertTrue(text.contains("template = \"x.html\""))
        assertFalse(text.contains("def get"))
        assertFalse(text.contains("@staticmethod"))
    }

    fun testDecoratorsAndAsync() {
        val model = model("shop/orders/views.py", views)
        val post = visibleText(model.block("method:OrderView.post"))
        assertTrue(post.startsWith("@staticmethod\n    async def post"))
        assertTrue(visibleText(model.block("method:create_order")).startsWith("def create_order(payload):"))
    }

    fun testOwnsLinks() {
        val model = model("shop/orders/views.py", views)
        assertSameElements(
            model.links,
            Link(LinkKind.OWNS, "module:shop.orders.views", "method:create_order"),
            Link(LinkKind.OWNS, "class:shop.orders.views.OrderView", "method:OrderView.get"),
            Link(LinkKind.OWNS, "class:shop.orders.views.OrderView", "method:OrderView.post"),
        )
    }

    fun testScriptWithoutDefs() {
        val model = model("tools/run.py", """
            import sys

            print(sys.argv)
            if __name__ == "__main__":
                main()
        """)
        assertSameElements(model.blocks.map { it.id }, "header", "module:tools.run")
        assertEquals("1 imports", model.block("header").title)
        val text = visibleText(model.block("module:tools.run"))
        assertTrue(text.contains("print(sys.argv)"))
        assertTrue(text.contains("if __name__ == \"__main__\":"))
    }

    fun testSameNameInModuleAndClass() {
        val model = model("shop/repo.py", """
            def save():
                pass

            class Repo:
                def save(self):
                    pass
        """)
        assertSameElements(model.blocks.map { it.id }, "module:shop.repo", "method:save", "class:shop.repo.Repo", "method:Repo.save")
    }

    fun testInitPyIsItsPackage() {
        val model = model("shop/orders/__init__.py", "x = 1")
        assertSameElements(model.blocks.map { it.id }, "module:shop.orders")
        assertEquals("module shop.orders", model.block("module:shop.orders").title)
    }

    fun testEmptyFile() {
        val model = model("shop/empty.py", "")
        assertSameElements(model.blocks.map { it.id }, "module:shop.empty")
        assertEquals(0, model.block("module:shop.empty").range.end)
    }

    fun testNestedDefsStayInside() {
        val model = model("shop/nested.py", """
            def outer():
                def inner():
                    pass
                class Local:
                    def hidden(self):
                        pass
                return inner

            class Repo:
                class Meta:
                    def meta_method(self):
                        pass

                def save(self):
                    def helper():
                        pass
        """)
        assertSameElements(model.blocks.map { it.id }, "module:shop.nested", "method:outer", "class:shop.nested.Repo", "method:Repo.save")
        assertTrue(visibleText(model.block("method:outer")).contains("def inner():"))
        assertTrue(visibleText(model.block("class:shop.nested.Repo")).contains("def meta_method(self):"))
        assertTrue(visibleText(model.block("method:Repo.save")).contains("def helper():"))
    }

    /** A property and its setter share a name; Python allows it, so the canvas must too. */
    fun testRedefinedNamesGetDistinctIds() {
        val model = model("shop/item.py", """
            class Item:
                @property
                def price(self):
                    return self._price

                @price.setter
                def price(self, value):
                    self._price = value
        """)
        assertSameElements(model.blocks.map { it.id }, "module:shop.item", "class:shop.item.Item", "method:Item.price", "method:Item.price#2")
    }

    fun testKeyOfAndModuleName() {
        addModule("shop/__init__.py", "")
        val file = open("shop/repo.py", """
            def save():
                pass

            class Repo:
                def save(self):
                    pass
        """) as PyFile
        assertEquals("shop.repo", PythonBlockBuilder.moduleName(file))
        assertEquals("save", PythonBlockBuilder.keyOf(file.findTopLevelFunction("save")!!))
        assertEquals("Repo.save", PythonBlockBuilder.keyOf(file.findTopLevelClass("Repo")!!.methods.single()))
    }

    fun testTheSupportBuildsWithTheBuilder() {
        val file = open("a.py", "x = 1")
        val result = PythonLanguageSupport().build(file, emptySet(), emptySet(), emptySet())
        assertTrue(result is BuildResult.Supported)
    }
}
