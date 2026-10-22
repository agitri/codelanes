package dev.codelanes.java

import dev.codelanes.model.BlockKind
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class JavaBlockBuilderCallsTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addClass("package com.acme; public interface OrderRepository { void save(Order o); }")
        addClass("package com.acme; public class Order {}")
        addClass("package com.acme; public class JpaOrderRepository implements OrderRepository { public void save(Order o) {} }")
        addClass("package com.acme; public class Mailer { public void send(Order o) {} public void send(Order o, int copies) {} }")
    }

    private val controller = """
        package com.acme;
        public class OrderController {
            private final OrderRepository repo;
            private final Mailer mailer;
            public OrderController(OrderRepository repo, Mailer mailer) { this.repo = repo; this.mailer = mailer; }
            public void create() {
                Order o = new Order();
                repo.save(o);
                mailer.send(o, 2);
            }
        }
    """.trimIndent()

    fun testPlusCallsShowsCalleesAndTheImplementationsOfInterfaceCalls() {
        val model = supported(controller, "OrderController.java", followed = setOf("method:create"))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:create", "callee:com.acme.OrderRepository::save")))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:create", "callee:com.acme.Mailer::send(Order, int)")))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "callee:com.acme.OrderRepository::save", "callee:com.acme.JpaOrderRepository::save")))
        assertEquals("Mailer::send(Order o, int copies)", model.block("callee:com.acme.Mailer::send(Order, int)").title)
    }

    fun testNoCalleesWithoutPlusCalls() {
        assertTrue(supported(controller, "OrderController.java").ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testAMethodAddedFromSearchGetsABlockAndCanBeFollowed() {
        val model = supported(controller, "OrderController.java",
            added = setOf("com.acme.JpaOrderRepository::save", "com.acme.Order::class"),
            followed = setOf("callee:com.acme.JpaOrderRepository::save"))
        assertEquals("JpaOrderRepository::save(Order o)", model.block("callee:com.acme.JpaOrderRepository::save").title)
        assertFalse(model.block("callee:com.acme.Order::class").collapsed)
    }

    fun testAnAddedMethodThatNoLongerExistsIsLeftOut() {
        val model = supported(controller, "OrderController.java", added = setOf("com.acme.Mailer::gone", "com.acme.Nope::x"))
        assertTrue(model.ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testAnInterfaceShowsItsImplementersAndTheirMethods() {
        addClass("package com.acme; public class MailNotifier implements Notifier { public void notify(Order o) {} public void other() {} }")
        val model = supported("package com.acme; public interface Notifier { void notify(Order o); }", "Notifier.java")
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "class:com.acme.Notifier", "implementer:com.acme.MailNotifier")))
        assertEquals(listOf("notify(Order o)"), model.block("implementer:com.acme.MailNotifier").summary)
        assertTrue(model.links.contains(Link(LinkKind.OVERRIDES, "implementation:com.acme.MailNotifier::notify", "method:notify",
            model.block("method:notify").range)))
    }

    fun testTheChainStopsAtTheCap() {
        // 12 classes, each calling the next: following them all stays within the 80-block cap and notes the 10-callee cap.
        val calls = (1..12).joinToString("\n") { "        new C$it().go();" }
        (1..12).forEach { addClass("package com.acme; public class C$it { public void go() {} }") }
        val model = supported("package com.acme;\npublic class Big {\n    void run() {\n$calls\n    }\n}\n", "Big.java", followed = setOf("method:run"))
        assertEquals(10, model.ofKind(BlockKind.CALLEE).size)
        assertEquals("and 2 more calls", model.block("more:calls:method:run").title)
    }
}
