package dev.codelanes.java

import dev.codelanes.flow.Flow
import dev.codelanes.flow.FlowEntry
import dev.codelanes.flow.FlowHop
import dev.codelanes.flow.FlowResolver
import dev.codelanes.flow.ResolvedFlow
import dev.codelanes.flow.ResolvedHop

class JavaFlowLookupTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addSpringStubs()
        myFixture.addFileToProject("com/acme/OrderController.java", """
            package com.acme;
            public class OrderController {
                private final OrderService service;
                public OrderController(OrderService service) { this.service = service; }
                public void create() { service.place(new Order()); }
                public void create(int n) {}
            }
        """.trimIndent())
        myFixture.addFileToProject("com/acme/OrderService.java", """
            package com.acme;
            import org.springframework.context.ApplicationEventPublisher;
            public class OrderService extends BaseService {
                private final OrderRepository repo;
                private final ApplicationEventPublisher events;
                public OrderService(OrderRepository repo, ApplicationEventPublisher events) { this.repo = repo; this.events = events; }
                public void place(Order o) { repo.save(o); events.publishEvent(new OrderPlaced(o)); }
                public void cancel(Order o) {}
            }
        """.trimIndent())
        myFixture.addFileToProject("com/acme/BaseService.java", "package com.acme;\npublic class BaseService { public void audit() {} }")
        myFixture.addFileToProject("com/acme/Order.java", "package com.acme;\npublic class Order {}")
        myFixture.addFileToProject("com/acme/OrderPlaced.java", "package com.acme;\npublic class OrderPlaced { public OrderPlaced(Order o) {} }")
        myFixture.addFileToProject("com/acme/OrderRepository.java", "package com.acme;\npublic interface OrderRepository { void save(Order o); }")
        myFixture.addFileToProject("com/acme/JpaOrderRepository.java", "package com.acme;\npublic class JpaOrderRepository implements OrderRepository { public void save(Order o) {} }")
        myFixture.addFileToProject("com/acme/SendOrderMail.java", """
            package com.acme;
            import org.springframework.context.event.EventListener;
            public class SendOrderMail { @EventListener public void on(OrderPlaced event) {} public void other(Order o) {} }
        """.trimIndent())
    }

    private val create = "com.acme.OrderController::create"
    private val place = "com.acme.OrderService::place"

    private fun resolve(vararg hops: FlowHop, entry: String = create): ResolvedFlow =
        FlowResolver.resolve(project, Flow("POST /orders", FlowEntry("POST /orders", entry), hops.toList()))

    fun testTheEntryOpensTheControllerOnItsMethod() {
        val resolved = resolve()
        assertNull(resolved.entryProblem)
        assertEquals("OrderController.java", resolved.entryFile!!.name)
        assertEquals("method:create()", resolved.entryId)
    }

    fun testAnOverloadIsPickedBySignatureAndABareNameTakesTheFirst() {
        assertEquals("method:create(int)", resolve(entry = "com.acme.OrderController::create(int)").entryId)
        assertEquals("method:create()", resolve(entry = create).entryId)
    }

    fun testProvenAndClaimedHops() {
        val resolved = resolve(
            FlowHop(create, place, "calls"),
            FlowHop(place, "com.acme.OrderPlaced::class", "dispatches"),
            FlowHop("com.acme.OrderPlaced::class", "com.acme.SendOrderMail::on", "listens"),
            FlowHop("com.acme.OrderRepository::save", "com.acme.JpaOrderRepository::save", "binds"),
            FlowHop(place, "com.acme.OrderService::cancel", "calls"),
            FlowHop("com.acme.OrderPlaced::class", "com.acme.SendOrderMail::other", "listens"),
        )
        assertEquals(listOf(
            ResolvedHop("method:create()", "callee:com.acme.OrderService::place", "calls", true),
            ResolvedHop("callee:com.acme.OrderService::place", "callee:com.acme.OrderPlaced::class", "dispatches", true),
            ResolvedHop("callee:com.acme.OrderPlaced::class", "callee:com.acme.SendOrderMail::on", "listens", true),
            ResolvedHop("callee:com.acme.OrderRepository::save", "callee:com.acme.JpaOrderRepository::save", "binds", true),
            ResolvedHop("callee:com.acme.OrderService::place", "callee:com.acme.OrderService::cancel", "calls", false),
            ResolvedHop("callee:com.acme.OrderPlaced::class", "callee:com.acme.SendOrderMail::other", "listens", false),
        ), resolved.hops)
        assertTrue(resolved.unknown.isEmpty())
    }

    fun testUnknownReferences() {
        val resolved = resolve(FlowHop(create, "com.acme.Nope::x", "calls"), FlowHop(create, "com.acme.OrderService::gone", "calls"))
        assertEquals(listOf("com.acme.Nope::x (no such class)", "com.acme.OrderService::gone (no such method)"), resolved.unknown)
        assertTrue(resolved.hops.isEmpty())
    }

    fun testAnInheritedMethodLandsOnTheClassThatDeclaresIt() {
        val resolved = resolve(FlowHop(create, "com.acme.OrderService::audit", "calls"))
        assertEquals("callee:com.acme.BaseService::audit", resolved.hops.single().toId)
    }

    fun testAConstructorStandsForTheClass() {
        val resolved = resolve(FlowHop(place, "com.acme.OrderPlaced::<init>", "dispatches"))
        assertEquals(ResolvedHop("callee:com.acme.OrderService::place", "callee:com.acme.OrderPlaced::class", "dispatches", true), resolved.hops.single())
    }

    fun testAPhpReferenceIsNotReadAsJava() {
        val resolved = resolve(FlowHop(create, "App\\Services\\OrderService::place", "calls"))
        assertEquals(listOf("App\\Services\\OrderService::place (no such class)"), resolved.unknown)
    }

    fun testParameterTypesMayBeGenericOrQualified() {
        addClass("package java.util; public interface List<E> {}")
        myFixture.addFileToProject("com/acme/Store.java",
            "package com.acme;\nimport java.util.List;\npublic class Store { public void save(Order o) {} public void save(List<Order> os) {} }")
        val resolved = resolve(
            FlowHop(create, "com.acme.Store::save(List<Order>)", "calls"),
            FlowHop(create, "com.acme.Store::save(com.acme.Order)", "calls"),
        )
        assertTrue(resolved.unknown.isEmpty())
        assertEquals(listOf("callee:com.acme.Store::save(List)", "callee:com.acme.Store::save(Order)"), resolved.hops.map { it.toId })
    }

    fun testANestedClassIsNotShownAsABlock() {
        myFixture.addFileToProject("com/acme/Outer.java", "package com.acme;\npublic class Outer { public static class Inner { public void m() {} } }")
        val resolved = resolve(FlowHop(create, "com.acme.Outer.Inner::m", "calls"))
        assertEquals(listOf("com.acme.Outer.Inner::m (nested classes aren't shown as blocks)"), resolved.unknown)
    }

    fun testAnEntryInALibraryWithoutSourceIsNotShown() {
        val resolved = resolve(entry = "junit.framework.TestCase::setUp")
        assertEquals("TestCase.class can't be shown as blocks: Compiled class without source", resolved.entryProblem)
        assertNull(resolved.entryId)
    }
}
