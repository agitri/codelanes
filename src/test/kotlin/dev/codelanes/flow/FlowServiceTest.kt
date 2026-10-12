package dev.codelanes.flow

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowServiceTest : BasePlatformTestCase() {
    private val shown = mutableListOf<String>()
    private var asked = 0
    private var pick: () -> VirtualFile? = { flowsFolder() }

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/OrderController.php", "<?php\nnamespace App;\n\nclass OrderController\n{\n    public function store(): void { (new OrderService())->place(); }\n}\n")
        myFixture.addFileToProject("src/OrderService.php", "<?php\nnamespace App;\n\nclass OrderService\n{\n    public function place(): void {}\n}\n")
        myFixture.addFileToProject("src/Mailer.php", "<?php\nnamespace App;\n\nclass Mailer\n{\n    public function send(): void {}\n}\n")
        val service = FlowService.getInstance(project)
        service.chooseFolder = { asked++; pick() }
        service.showOnCanvas = { _: VirtualFile, _: ResolvedFlow, title: String -> shown += title; true }
    }

    private fun flowsFolder(): VirtualFile =
        WriteAction.computeAndWait<VirtualFile, RuntimeException> { VfsUtil.createDirectoryIfMissing(project.guessProjectDir()!!, ".codelanes/flows")!! }

    private fun flow(name: String = "POST /orders", entry: String = "App\\OrderController::store", vararg hops: FlowHop) =
        Flow(name, FlowEntry("POST /orders", entry), hops.toList())

    private val calls = FlowHop("App\\OrderController::store", "App\\OrderService::place", "calls")
    private val claimed = FlowHop("App\\OrderService::place", "App\\Mailer::send", "dispatches")

    fun testAFlowIsShownSavedAndReported() {
        val reply = FlowService.getInstance(project).show(flow("POST /orders", "App\\OrderController::store", calls, claimed))
        assertEquals(listOf("POST /orders"), shown)
        assertEquals("Shown \"POST /orders\": 2 hops, 1 proven, 1 claimed. Saved to .codelanes/flows/post-orders.flow.json.", reply)
        assertNotNull(FlowFiles.getInstance(project).find("POST /orders"))
    }

    fun testUnknownReferencesAreListedForTheAi() {
        val reply = FlowService.getInstance(project).show(flow("GET /x", "App\\OrderController::store", FlowHop("App\\OrderController::store", "App\\Nope::x", "calls")))
        assertTrue(reply, reply.endsWith(" Unknown, not drawn: App\\Nope::x (no such class)."))
    }

    fun testAMissingEntryShowsNothingAndSaysWhy() {
        val reply = FlowService.getInstance(project).show(flow("GET /y", "App\\Nope::x"))
        assertEquals("Nothing shown: the entry App\\Nope::x (no such class).", reply)
        assertTrue(shown.isEmpty())
        assertEquals(0, asked)
    }

    fun testCancellingTheFolderPickerShowsButDoesNotSave() {
        pick = { null }
        val reply = FlowService.getInstance(project).show(flow("GET /z"))
        assertTrue(reply, reply.endsWith("Not saved: the user cancelled choosing a folder."))
        assertNull(FlowFiles.getInstance(project).find("GET /z"))
    }

    fun testAskingAgainUpdatesTheSavedFlowWithoutAsking() {
        val service = FlowService.getInstance(project)
        service.show(flow("PUT /again"))
        service.show(flow("PUT /again", "App\\OrderController::store", calls))
        assertEquals(1, asked)
        assertEquals(1, FlowFiles.getInstance(project).find("PUT /again")!!.flow.hops.size)
    }

    fun testInputWithAnUnknownHopKindIsRejectedWithTheAllowedKinds() {
        val error = FlowInput.check("x", "x", "App\\A::b", listOf(FlowHop("App\\A::b", "App\\C::d", "fires"))).error!!
        assertTrue(error, error.contains("fires") && error.contains("routes, middleware, dispatches, listens, binds, calls"))
        assertNotNull(FlowInput.check(" ", "x", "App\\A::b", emptyList()).error)
        assertEquals("POST /a", FlowInput.check("POST /a", "", "App\\A::b", emptyList()).flow!!.entry.label)
    }

    fun testHopsAreReadFromArrowText() {
        assertEquals(FlowHop("App\\A::b", "App\\C::d", "dispatches"), FlowInput.parseHop("App\\A::b --dispatches--> App\\C::d"))
        assertEquals(FlowHop("\\App\\A::b", "App\\C::d", "calls"), FlowInput.parseHop("  \\App\\A::b -- calls --> App\\C::d "))
        assertNull(FlowInput.parseHop("App\\A::b -> App\\C::d"))
        val error = FlowInput.check("x", "", "App\\A::b", emptyList(), listOf("App\\A::b -> App\\C::d")).error!!
        assertTrue(error, error.contains("App\\A::b -> App\\C::d") && error.contains("--kind-->"))
    }

    fun testAFolderThatCannotBeWrittenIsReportedNotThrown() {
        pick = { WriteAction.computeAndWait<VirtualFile, RuntimeException> { project.guessProjectDir()!!.createChildData(this, "not-a-folder.txt") } }
        val reply = FlowService.getInstance(project).show(flow("GET /io"))
        assertTrue(reply, reply.contains("Not saved:"))
    }
}
