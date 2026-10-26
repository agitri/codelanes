package dev.codelanes.canvas

import dev.codelanes.flow.ResolvedHop
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowOverlayTest {
    private fun block(id: String, kind: BlockKind = BlockKind.CALLEE) = Block(id, kind, id, "/x.php", SourceRange(0, 0))

    private val store = "method:store"
    private val auth = "callee:\\App\\Authenticate::handle"
    private val session = "callee:\\App\\StartSession::handle"
    private val repo = "callee:\\App\\Repo::save"
    private val eloquent = "callee:\\App\\EloquentRepo::save"
    private val service = "callee:\\App\\Service::create"

    private fun model(vararg links: Link) = BlockModel(
        listOf(block("class:\\App\\Controller", BlockKind.CLASS), block(store, BlockKind.METHOD), block("method:other", BlockKind.METHOD), block(auth), block(session),
            block(repo), block(eloquent), block(service), block("dependency:\\App\\Service", BlockKind.DEPENDENCY),
            block("dependency:\\App\\Other", BlockKind.DEPENDENCY)),
        links.toList(),
    )

    private fun flowLinks(result: BlockModel) = result.links.filter { it.kind == LinkKind.FLOW }

    @Test
    fun theRouteLandsOnTheFirstMiddlewareBeforeTheEntry() {
        val overlay = FlowOverlay("POST /invoices", store, listOf(
            ResolvedHop(session, auth, "middleware", false),
            ResolvedHop(auth, store, "middleware", false),
        ), emptyList())
        val links = flowLinks(overlay.applyTo(model(), "/x.php"))
        assertTrue(links.toString(), Link(LinkKind.FLOW, FlowOverlay.ENTRY_ID, session, label = "routes", claimed = true) in links)
        assertFalse(links.toString(), links.any { it.from == FlowOverlay.ENTRY_ID && it.to == store })
    }

    @Test
    fun withoutMiddlewareTheRouteLandsOnTheEntry() {
        val overlay = FlowOverlay("POST /invoices", store, emptyList(), emptyList())
        val links = flowLinks(overlay.applyTo(model(), "/x.php"))
        assertEquals(listOf(Link(LinkKind.FLOW, FlowOverlay.ENTRY_ID, store, label = "routes", claimed = true)), links)
    }

    @Test
    fun middlewareThatLoopsDoesNotHang() {
        val overlay = FlowOverlay("POST /invoices", store, listOf(
            ResolvedHop(auth, store, "middleware", false),
            ResolvedHop(store, auth, "middleware", false),
        ), emptyList())
        val links = flowLinks(overlay.applyTo(model(), "/x.php"))
        assertTrue(links.toString(), Link(LinkKind.FLOW, FlowOverlay.ENTRY_ID, auth, label = "routes", claimed = true) in links)
    }

    @Test
    fun aBindsHopReplacesTheImplementedByLineBetweenTheSameBlocks() {
        val overlay = FlowOverlay("POST /invoices", store, listOf(ResolvedHop(repo, eloquent, "binds", true)), emptyList())
        val result = overlay.applyTo(model(Link(LinkKind.IMPLEMENTED_BY, repo, eloquent)), "/x.php")
        assertFalse(result.links.toString(), result.links.any { it.kind == LinkKind.IMPLEMENTED_BY })
        assertTrue(Link(LinkKind.FLOW, repo, eloquent, label = "binds") in result.links)
    }

    @Test
    fun theEntryBlockSaysTheRouteIsNotChecked() {
        val overlay = FlowOverlay("POST /invoices", store, emptyList(), emptyList())
        val entry = overlay.applyTo(model(), "/x.php").blocks.single { it.id == FlowOverlay.ENTRY_ID }
        assertEquals(listOf(FlowOverlay.ENTRY_NOTE), entry.summary)
    }

    @Test
    fun theClassToMethodLineIsLeftOutForMethodsOnTheFlow() {
        val overlay = FlowOverlay("POST /invoices", store, emptyList(), emptyList())
        val result = overlay.applyTo(model(Link(LinkKind.OWNS, "class:\\App\\Controller", store), Link(LinkKind.OWNS, "class:\\App\\Controller", "method:other")), "/x.php")
        assertFalse(result.links.toString(), result.links.any { it.kind == LinkKind.OWNS && it.to == store })
        assertTrue(result.links.any { it.kind == LinkKind.OWNS && it.to == "method:other" })
    }

    @Test
    fun anInjectedClassWhoseMethodIsOnTheFlowIsNotShownTwice() {
        val overlay = FlowOverlay("POST /invoices", store, listOf(ResolvedHop(store, service, "calls", true)), emptyList())
        val result = overlay.applyTo(model(
            Link(LinkKind.INJECTS, "dependency:\\App\\Service", "class:\\App\\Controller"),
            Link(LinkKind.INJECTS, "dependency:\\App\\Other", "class:\\App\\Controller"),
        ), "/x.php")
        val ids = result.blocks.map { it.id }
        assertFalse(ids.toString(), "dependency:\\App\\Service" in ids)
        assertFalse(result.links.any { it.from == "dependency:\\App\\Service" })
        assertTrue("dependency:\\App\\Other" in ids)
    }

    @Test
    fun aPythonClassWhoseMethodIsOnTheFlowIsNotShownTwice() {
        val view = "method:create_order"
        val place = "callee:shop.service::OrderService.place"
        val model = BlockModel(
            listOf(block("module:shop.views", BlockKind.MODULE), block(view, BlockKind.METHOD), block(place),
                block("dependency:shop.service.OrderService", BlockKind.DEPENDENCY), block("dependency:shop.service.Other", BlockKind.DEPENDENCY)),
            emptyList(),
        )
        val result = FlowOverlay("POST /orders", view, listOf(ResolvedHop(view, place, "calls", true)), emptyList()).applyTo(model, "/views.py")
        val ids = result.blocks.map { it.id }
        assertFalse(ids.toString(), "dependency:shop.service.OrderService" in ids)
        assertTrue("dependency:shop.service.Other" in ids)
    }
}
