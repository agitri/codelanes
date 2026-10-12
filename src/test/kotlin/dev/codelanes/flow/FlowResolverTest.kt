package dev.codelanes.flow

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowResolverTest : BasePlatformTestCase() {
    private val d = '$'

    override fun setUp() {
        super.setUp()
        add("Http/Controllers/OrderController.php", "App\\Http\\Controllers", """
            use App\Services\OrderService;
            class OrderController
            {
                public function __construct(private OrderService ${d}orders) {}
                public function store(): string { return ${d}this->orders->place(); }
                public function show(): string { return 'x'; }
            }
        """)
        add("Services/OrderService.php", "App\\Services", """
            use App\Events\OrderPlaced;
            class OrderService
            {
                public function place(): string { event(new OrderPlaced()); return 'ok'; }
                public function cancel(): void {}
                public function ship(): void { event(new \App\Events\OrderShipped()); event(new \App\Events\OrderPaid(1)); }
            }
        """)
        add("Events/OrderShipped.php", "App\\Events", "class OrderShipped\n{\n}")
        add("Events/BaseEvent.php", "App\\Events", "class BaseEvent\n{\n    public function __construct(public int ${d}id) {}\n}")
        add("Events/OrderPaid.php", "App\\Events", "class OrderPaid extends BaseEvent\n{\n}")
        add("Listeners/NotifyShipped.php", "App\\Listeners", "use App\\Events\\OrderShipped;\nclass NotifyShipped\n{\n    public function handle(OrderShipped ${d}e): void {}\n}")
        add("Listeners/NotifyPaid.php", "App\\Listeners", "use App\\Events\\OrderPaid;\nclass NotifyPaid\n{\n    public function handle(OrderPaid ${d}e): void {}\n}")
        add("Events/OrderPlaced.php", "App\\Events", "class OrderPlaced\n{\n    public function __construct() {}\n}")
        add("Listeners/SendOrderMail.php", "App\\Listeners", """
            use App\Events\OrderPlaced;
            class SendOrderMail
            {
                public function handle(OrderPlaced ${d}event): void {}
            }
        """)
        add("Repositories/OrderRepository.php", "App\\Repositories", "interface OrderRepository\n{\n    public function save(): void;\n}")
        add("Repositories/BaseRepository.php", "App\\Repositories", "class BaseRepository\n{\n    public function all(): array { return []; }\n}")
        add("Repositories/EloquentOrderRepository.php", "App\\Repositories",
            "class EloquentOrderRepository extends BaseRepository implements OrderRepository\n{\n    public function save(): void {}\n}")
        add("Http/Middleware/Authenticate.php", "App\\Http\\Middleware",
            "class Authenticate\n{\n    public function handle(${d}request, ${d}next) { return ${d}next(${d}request); }\n}")
        add("Two.php", "App", "class One\n{\n    public function a(): void {}\n}\nclass Two {}")
    }

    private fun add(path: String, namespace: String, body: String) {
        myFixture.addFileToProject("src/$path", "<?php\nnamespace $namespace;\n\n${body.trimIndent()}\n")
    }

    private fun resolve(vararg hops: FlowHop, entry: String = "App\\Http\\Controllers\\OrderController::store"): ResolvedFlow =
        FlowResolver.resolve(project, Flow("POST /orders", FlowEntry("POST /orders", entry), hops.toList()))

    private fun hop(from: String, to: String, kind: String) = FlowHop(from, to, kind)

    private val store = "App\\Http\\Controllers\\OrderController::store"
    private val place = "App\\Services\\OrderService::place"
    private val placed = "App\\Events\\OrderPlaced::__construct"
    private val mail = "App\\Listeners\\SendOrderMail::handle"

    fun testTheEntryOpensItsOwnFileAndIsAMethodBlockThere() {
        val flow = resolve()
        assertEquals("OrderController.php", flow.entryFile?.name)
        assertEquals("method:store", flow.entryId)
        assertNull(flow.entryProblem)
    }

    fun testACallThatIsInTheCodeIsProven() {
        val h = resolve(hop(store, place, "calls")).hops.single()
        assertEquals(ResolvedHop("method:store", "callee:\\App\\Services\\OrderService::place", "calls", proven = true), h)
        assertTrue("\\App\\Services\\OrderService::place" in resolve(hop(store, place, "calls")).added)
    }

    fun testACallThatIsNotInTheCodeIsOnlyClaimed() {
        assertFalse(resolve(hop("App\\Http\\Controllers\\OrderController::show", "App\\Services\\OrderService::cancel", "calls")).hops.single().proven)
    }

    fun testDispatchingIsProvenByNewingTheEvent() {
        assertTrue(resolve(hop(place, placed, "dispatches")).hops.single().proven)
        assertFalse(resolve(hop("App\\Services\\OrderService::cancel", placed, "dispatches")).hops.single().proven)
    }

    fun testListeningIsProvenByTheFirstParameterType() {
        assertTrue(resolve(hop(placed, mail, "listens")).hops.single().proven)
        assertFalse(resolve(hop(place, mail, "listens")).hops.single().proven)
    }

    fun testABindingIsProvenWhenTheClassImplementsTheInterface() {
        assertTrue(resolve(hop("App\\Repositories\\OrderRepository::save", "App\\Repositories\\EloquentOrderRepository::save", "binds")).hops.single().proven)
        assertFalse(resolve(hop("App\\Repositories\\OrderRepository::save", mail, "binds")).hops.single().proven)
    }

    fun testRoutesAndMiddlewareThroughNextAreClaimed() {
        assertFalse(resolve(hop("App\\Http\\Middleware\\Authenticate::handle", store, "middleware")).hops.single().proven)
        assertFalse(resolve(hop("App\\Http\\Middleware\\Authenticate::handle", store, "routes")).hops.single().proven)
    }

    fun testUnknownReferencesAreReportedAndTheirHopsDropped() {
        val flow = resolve(hop(store, "App\\Nope::x", "calls"), hop(store, "App\\Services\\OrderService::nope", "calls"), hop(store, place, "calls"))
        assertEquals(1, flow.hops.size)
        assertEquals(listOf("App\\Nope::x (no such class)", "App\\Services\\OrderService::nope (no such method)"), flow.unknown)
    }

    fun testAnInheritedMethodIsTheBlockOfTheClassThatDeclaresIt() {
        val h = resolve(hop(store, "App\\Repositories\\EloquentOrderRepository::all", "calls")).hops.single()
        assertEquals("callee:\\App\\Repositories\\BaseRepository::all", h.toId)
    }

    fun testALeadingBackslashIsFine() {
        assertEquals(1, resolve(hop("\\$store", "\\$place", "calls")).hops.size)
    }

    fun testTheOpenClassesConstructorIsTheClassBlock() {
        assertEquals("class:\\App\\Http\\Controllers\\OrderController", resolve(hop(store, "App\\Http\\Controllers\\OrderController::__construct", "calls")).hops.single().toId)
    }

    fun testAnEntryThatCannotBeShownAsBlocksSaysWhy() {
        val flow = resolve(entry = "App\\One::a")
        assertNotNull(flow.entryProblem)
        assertNull(flow.entryId)
    }

    fun testAnEntryThatDoesNotExistSaysSo() {
        assertEquals("App\\Nope::x (no such class)", resolve(entry = "App\\Nope::x").entryProblem)
    }

    private val ship = "App\\Services\\OrderService::ship"

    fun testAnEventWithoutAConstructorIsAClassBlockAndItsHopsAreProven() {
        val flow = resolve(hop(ship, "App\\Events\\OrderShipped::class", "dispatches"), hop("App\\Events\\OrderShipped::class", "App\\Listeners\\NotifyShipped::handle", "listens"))
        assertEquals(flow.unknown.toString(), 2, flow.hops.size)
        assertEquals("callee:\\App\\Events\\OrderShipped::class", flow.hops[0].toId)
        assertTrue(flow.hops.all { it.proven })
        assertTrue("\\App\\Events\\OrderShipped::class" in flow.added)
        assertEquals("callee:\\App\\Events\\OrderShipped::class", resolve(hop(ship, "App\\Events\\OrderShipped::__construct", "dispatches")).hops.single().toId)
    }

    fun testAnInheritedConstructorStandsForTheClassThatWasNamed() {
        val flow = resolve(hop(ship, "App\\Events\\OrderPaid::__construct", "dispatches"), hop("App\\Events\\OrderPaid::__construct", "App\\Listeners\\NotifyPaid::handle", "listens"))
        assertEquals("callee:\\App\\Events\\OrderPaid::class", flow.hops[0].toId)
        assertTrue(flow.hops.toString(), flow.hops.all { it.proven })
    }
}
