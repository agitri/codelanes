package dev.codelanes.python

import dev.codelanes.flow.Flow
import dev.codelanes.flow.FlowEntry
import dev.codelanes.flow.FlowHop
import dev.codelanes.flow.FlowResolver
import dev.codelanes.flow.ResolvedFlow
import dev.codelanes.flow.ResolvedHop
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import com.jetbrains.python.psi.PyClass

/** Python flow references (`shop.service::OrderService.place`) and the hops the code proves. */
class PythonFlowLookupTest : PythonTestCase() {
    private val support = PythonLanguageSupport()

    override fun setUp() {
        super.setUp()
        // No SDK: django's signal API stubbed as project modules.
        addModule("django/__init__.py", "")
        addModule("django/dispatch.py", """
            class Signal:
                def send(self, sender, **kwargs): ...
                def send_robust(self, sender, **kwargs): ...


            def receiver(signal, **kwargs):
                def wrap(f):
                    return f
                return wrap
        """)
        addModule("shop/__init__.py", "")
        addModule("shop/base.py", """
            class Base:
                def save(self):
                    pass
        """)
        addModule("shop/repo.py", """
            from shop.base import Base


            class Repo(Base):
                def save(self):
                    pass

                def load(self):
                    pass


            class Child(Base):
                pass
        """)
        addModule("shop/events.py", """
            from django.dispatch import Signal


            class OrderPlaced:
                def __init__(self, order):
                    self.order = order


            class Shipped:
                pass


            order_placed = Signal()
        """)
        addModule("shop/service.py", """
            from shop.repo import Repo
            from shop.events import OrderPlaced, order_placed


            class OrderService:
                def __init__(self, repo: Repo):
                    self.repo = repo

                def place(self, order):
                    self.repo.save()
                    order_placed.send(sender=self.__class__, order=order)
                    return OrderPlaced(order)

                def cancel(self, order):
                    pass
        """)
        addModule("shop/listeners.py", """
            from django.dispatch import receiver
            from shop.events import OrderPlaced, Shipped, order_placed


            @receiver(order_placed)
            def send_mail(sender, **kwargs):
                pass


            class Audit:
                def on_placed(self, event: OrderPlaced):
                    pass

                def on_shipped(self, event: Shipped):
                    pass


            def unrelated(sender, **kwargs):
                pass
        """)
        addModule("shop/views.py", """
            from shop.service import OrderService
            from shop.repo import Repo


            def create_order(request):
                service = OrderService(Repo())
                return service.place(request)


            def health(request):
                pass
        """)
    }

    private fun find(reference: String): FlowLookup? = PythonFlowLookup.find(project, reference, support)

    private fun found(reference: String): FlowTarget = (find(reference) as FlowLookup.Found).target

    private fun proves(kind: String, from: String, to: String): Boolean = support.proves(kind, found(from), found(to))

    private val create = "shop.views::create_order"
    private val place = "shop.service::OrderService.place"

    private fun resolve(vararg hops: FlowHop, entry: String = create): ResolvedFlow =
        FlowResolver.resolve(project, Flow("POST /orders", FlowEntry("POST /orders", entry), hops.toList()))

    fun testAModuleFunction() {
        val target = found(create)
        assertEquals("shop.views", target.classFqn)
        assertEquals("shop.views", target.declaringFqn)
        assertEquals("create_order", target.methodKey)
        assertFalse(target.isConstructor)
        assertEquals("views.py", target.file.name)
    }

    fun testAMethod() {
        val target = found(place)
        assertEquals("shop.service", target.classFqn)
        assertEquals("shop.service", target.declaringFqn)
        assertEquals("OrderService.place", target.methodKey)
        assertEquals("OrderService", (target.classElement as PyClass).name)
    }

    fun testAClassAndItsConstructorStandForTheClassBlock() {
        val cls = found("shop.service::OrderService")
        assertNull(cls.methodKey)
        assertEquals("shop.service", cls.classFqn)
        assertEquals("OrderService", (cls.classElement as PyClass).name)
        val init = found("shop.service::OrderService.__init__")
        assertTrue(init.isConstructor)
        assertEquals("OrderService.__init__", init.methodKey)
        assertEquals("OrderService", (init.classElement as PyClass).name)
        assertEquals("class:shop.service.OrderService", support.blockIdOf(init, "shop.service"))
        assertEquals("callee:shop.service.OrderService::class", support.blockIdOf(cls, "shop.views"))
    }

    fun testAClassWithoutItsOwnInitStillHasAConstructor() {
        val init = found("shop.events::Shipped.__init__")
        assertTrue(init.isConstructor)
        assertEquals("callee:shop.events.Shipped::class", support.blockIdOf(init, "shop.views"))
    }

    fun testAnInheritedMethodLandsOnTheClassThatDefinesIt() {
        val target = found("shop.repo::Child.save")
        assertEquals("shop.repo", target.classFqn)
        assertEquals("shop.base", target.declaringFqn)
        assertEquals("Base.save", target.methodKey)
        assertEquals("callee:shop.base::Base.save", support.blockIdOf(target, "shop.views"))
    }

    fun testAPackageInitIsAModule() {
        addModule("shop/orders/__init__.py", "def make():\n    pass\n")
        assertEquals("shop.orders", found("shop.orders::make").declaringFqn)
    }

    fun testMissingReferences() {
        assertEquals(FlowLookup.Missing("shop.views::nope (no such function)", ownerFound = true), find("shop.views::nope"))
        assertEquals(FlowLookup.Missing("shop.views::Nope.place (no such class)", ownerFound = true), find("shop.views::Nope.place"))
        assertEquals(FlowLookup.Missing("shop.service::OrderService.gone (no such method)", ownerFound = true), find("shop.service::OrderService.gone"))
    }

    fun testReferencesThatAreNotPython() {
        assertNull(find("App\\Services\\OrderService::place"))
        assertNull(find("shop\\views::create_order"))
        assertNull(find("com.acme.OrderService::place"))
        assertNull(find("shop.nope::place"))
    }

    fun testCallsAreProvenByACallOrAnInstantiation() {
        assertTrue(proves("calls", create, place))
        assertTrue(proves("calls", place, "shop.repo::Repo.save"))
        assertTrue(proves("calls", create, "shop.service::OrderService"))
        assertTrue(proves("calls", create, "shop.service::OrderService.__init__"))
        assertTrue(proves("middleware", create, place))
        assertFalse(proves("calls", place, "shop.service::OrderService.cancel"))
        assertFalse(proves("calls", create, "shop.events::OrderPlaced"))
    }

    fun testBindsIsProvenByAnOverride() {
        assertTrue(proves("binds", "shop.base::Base.save", "shop.repo::Repo.save"))
        assertFalse(proves("binds", "shop.base::Base.save", "shop.repo::Repo.load"))
        assertFalse(proves("binds", "shop.base::Base.save", "shop.repo::Child.save"))
        assertFalse(proves("binds", "shop.repo::Repo.save", "shop.base::Base.save"))
    }

    fun testDispatchesIsProvenBySendingASignalOrCreatingTheEvent() {
        assertTrue(proves("dispatches", place, "django.dispatch::Signal"))
        assertTrue(proves("dispatches", place, "shop.events::OrderPlaced"))
        assertTrue(proves("dispatches", place, "shop.events::OrderPlaced.__init__"))
        assertFalse(proves("dispatches", place, "shop.events::Shipped"))
        assertFalse(proves("dispatches", create, "django.dispatch::Signal"))
    }

    fun testListensIsProvenByAReceiverOrAnAnnotatedParameter() {
        assertTrue(proves("listens", "django.dispatch::Signal", "shop.listeners::send_mail"))
        assertFalse(proves("listens", "django.dispatch::Signal", "shop.listeners::unrelated"))
        assertTrue(proves("listens", "shop.events::OrderPlaced", "shop.listeners::Audit.on_placed"))
        assertFalse(proves("listens", "shop.events::OrderPlaced", "shop.listeners::Audit.on_shipped"))
        assertFalse(proves("listens", "shop.events::Shipped", "shop.listeners::send_mail"))
    }

    fun testRoutesAreNeverProven() {
        assertFalse(proves("routes", create, place))
    }

    fun testAWholeFlow() {
        val resolved = resolve(
            FlowHop(create, place, "calls"),
            FlowHop(place, "shop.events::OrderPlaced", "dispatches"),
            FlowHop("shop.events::OrderPlaced", "shop.listeners::Audit.on_placed", "listens"),
            FlowHop(place, "shop.service::OrderService.cancel", "calls"),
            FlowHop(create, "shop.views::gone", "calls"),
        )
        assertNull(resolved.entryProblem)
        assertEquals("views.py", resolved.entryFile!!.name)
        assertEquals("method:create_order", resolved.entryId)
        assertEquals(listOf(
            ResolvedHop("method:create_order", "callee:shop.service::OrderService.place", "calls", true),
            ResolvedHop("callee:shop.service::OrderService.place", "callee:shop.events.OrderPlaced::class", "dispatches", true),
            ResolvedHop("callee:shop.events.OrderPlaced::class", "callee:shop.listeners::Audit.on_placed", "listens", true),
            ResolvedHop("callee:shop.service::OrderService.place", "callee:shop.service::OrderService.cancel", "calls", false),
        ), resolved.hops)
        // The language that knows the module explains what's missing, not one that found no class at all.
        assertEquals(listOf("shop.views::gone (no such function)"), resolved.unknown)
        assertTrue("shop.service::OrderService.place" in resolved.added)
    }

    fun testAJavaReferenceStillReachesJava() {
        myFixture.addFileToProject("com/acme/Foo.java", "package com.acme;\npublic class Foo { public void bar() {} }")
        val resolved = resolve(entry = "com.acme.Foo::bar")
        assertNull(resolved.entryProblem)
        assertEquals("Foo.java", resolved.entryFile!!.name)
        assertEquals("method:bar", resolved.entryId)
    }

    fun testAClassIsNotAnEntry() {
        assertEquals("shop.service::OrderService (not a method)", resolve(entry = "shop.service::OrderService").entryProblem)
    }
}
