package dev.codelanes.python

import dev.codelanes.flow.Flow
import dev.codelanes.flow.FlowEntry
import dev.codelanes.flow.FlowHop
import dev.codelanes.flow.FlowResolver
import dev.codelanes.flow.ResolvedFlow
import dev.codelanes.flow.ResolvedHop
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.jetbrains.python.psi.PyClass
import java.nio.file.Files

/** Python flow references (`shop.service::OrderService.place`) and the hops the code proves. */
class PythonFlowLookupTest : PythonTestCase() {
    private val support = PythonLanguageSupport()

    override fun getProjectDescriptor(): LightProjectDescriptor = DJANGO_LIBRARY

    override fun setUp() {
        super.setUp()
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
            order_shipped = Signal()
        """)
        addModule("shop/service.py", """
            from shop import events
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

                def ship(self, order):
                    events.order_shipped.send_robust(sender=None, order=order)
        """)
        addModule("shop/listeners.py", """
            from django.dispatch import receiver
            from shop.events import OrderPlaced, Shipped, order_placed, order_shipped


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


            @receiver(order_shipped)
            def track(sender, **kwargs):
                pass
        """)
        addModule("shop/log.py", """
            from django.dispatch import receiver
            from shop import events


            @receiver([events.order_placed, events.order_shipped], dispatch_uid="log")
            def log_all(sender, **kwargs):
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
    }

    fun testAnUnknownModuleIsMissingButLeavesTheReferenceToOtherLanguages() {
        assertEquals(FlowLookup.Missing("shop.nope::place (no such module)"), find("shop.nope::place"))
        assertEquals(FlowLookup.Missing("com.acme.OrderService::place (no such module)"), find("com.acme.OrderService::place"))
        // Library code isn't a block: django is in a library root here, as in site-packages.
        assertEquals(FlowLookup.Missing("django.dispatch::Signal (no such module)"), find("django.dispatch::Signal"))
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

    fun testDispatchesIsProvenByCreatingTheEvent() {
        assertTrue(proves("dispatches", place, "shop.events::OrderPlaced"))
        assertTrue(proves("dispatches", place, "shop.events::OrderPlaced.__init__"))
        assertFalse(proves("dispatches", place, "shop.events::Shipped"))
    }

    fun testASignalHopIsProvenFromTheSenderToAReceiverOfThatSameSignal() {
        val sendMail = "shop.listeners::send_mail"
        val track = "shop.listeners::track"
        val logAll = "shop.log::log_all"
        val ship = "shop.service::OrderService.ship"
        for (kind in listOf("dispatches", "listens")) {
            assertTrue(kind, proves(kind, place, sendMail))
            assertFalse(kind, proves(kind, place, track)) // a receiver of another signal
            assertTrue(kind, proves(kind, ship, track)) // sent as events.order_shipped, with send_robust
            assertFalse(kind, proves(kind, ship, sendMail))
            assertTrue(kind, proves(kind, place, logAll)) // a list of signals, written qualified
            assertTrue(kind, proves(kind, ship, logAll))
            assertFalse(kind, proves(kind, create, sendMail)) // sends nothing itself
            assertFalse(kind, proves(kind, place, "shop.listeners::unrelated"))
        }
    }

    fun testListensIsProvenByAnAnnotatedParameter() {
        assertTrue(proves("listens", "shop.events::OrderPlaced", "shop.listeners::Audit.on_placed"))
        assertFalse(proves("listens", "shop.events::OrderPlaced", "shop.listeners::Audit.on_shipped"))
        assertFalse(proves("listens", "shop.events::OrderPlaced", "shop.listeners::send_mail"))
    }

    fun testRoutesAreNeverProven() {
        assertFalse(proves("routes", create, place))
    }

    fun testAWholeFlow() {
        val resolved = resolve(
            FlowHop(create, place, "calls"),
            FlowHop(place, "shop.events::OrderPlaced", "dispatches"),
            FlowHop("shop.events::OrderPlaced", "shop.listeners::Audit.on_placed", "listens"),
            FlowHop(place, "shop.listeners::send_mail", "dispatches"),
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
            ResolvedHop("callee:shop.service::OrderService.place", "callee:shop.listeners::send_mail", "dispatches", true),
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

    companion object {
        /** django's signal API in a library root (as in site-packages), so it's never project code. */
        val DJANGO_LIBRARY = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val dir = Files.createTempDirectory("codelanes-django")
                Files.createDirectories(dir.resolve("django"))
                Files.writeString(dir.resolve("django/__init__.py"), "")
                Files.writeString(dir.resolve("django/dispatch.py"), """
                    class Signal:
                        def send(self, sender, **kwargs): ...
                        def send_robust(self, sender, **kwargs): ...


                    def receiver(signal, **kwargs):
                        def wrap(f):
                            return f
                        return wrap
                """.trimIndent())
                val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
                val library = model.moduleLibraryTable.createLibrary("django")
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.CLASSES)
                    commit()
                }
            }
        }
    }
}
