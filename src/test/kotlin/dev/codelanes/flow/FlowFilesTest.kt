package dev.codelanes.flow

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FlowFilesTest : BasePlatformTestCase() {
    private val flow = Flow(
        "POST /orders",
        FlowEntry("POST /orders", "App\\Http\\Controllers\\OrderController::store"),
        listOf(FlowHop("App\\Services\\OrderService::place", "App\\Listeners\\SendOrderMail::handle", "listens")),
    )

    private fun folder(relative: String): VirtualFile =
        WriteAction.computeAndWait<VirtualFile, RuntimeException> { VfsUtil.createDirectoryIfMissing(project.guessProjectDir()!!, relative)!! }

    private fun write(relative: String, text: String) = WriteAction.runAndWait<RuntimeException> {
        val dir = VfsUtil.createDirectoryIfMissing(project.guessProjectDir()!!, relative.substringBeforeLast('/'))!!
        VfsUtil.saveText(dir.findChild(relative.substringAfterLast('/')) ?: dir.createChildData(this, relative.substringAfterLast('/')), text)
    }

    fun testAFlowIsWrittenAsItsOwnDotFileAndReadBack() {
        val files = FlowFiles.getInstance(project)
        val path = files.write(folder(".codelanes/flows"), flow)!!
        assertTrue(path, path.endsWith(".codelanes/flows/post-orders.flow.json"))
        assertEquals(FlowRead.Ok(flow), files.read(path))
        assertTrue(SavedFlow(flow, path) in files.list())
        assertEquals(path, files.find("POST /orders")?.path)
    }

    fun testFlowsInARememberedFolderAreFoundToo() {
        val files = FlowFiles.getInstance(project)
        val private = folder("private-flows")
        val path = files.write(private, flow.copy(name = "GET /mine"))!!
        assertTrue(files.list().any { it.path == path })
        assertTrue(private.path in files.folders())
    }

    fun testABrokenFlowFileIsNeverOverwritten() {
        write(".codelanes/flows/broken.flow.json", "<<<<<<< HEAD\n{")
        val files = FlowFiles.getInstance(project)
        val path = project.guessProjectDir()!!.path + "/.codelanes/flows/broken.flow.json"
        assertEquals(FlowRead.Broken, files.read(path))
        assertFalse(files.overwrite(path, flow))
        assertTrue(String(project.guessProjectDir()!!.findFileByRelativePath(".codelanes/flows/broken.flow.json")!!.contentsToByteArray()).startsWith("<<<<<<<"))
    }

    fun testAFileFromANewerCodeLanesIsRefusedNotMisread() {
        write(".codelanes/flows/future.flow.json", """{"version": 2, "name": "x", "entry": {"label": "x", "method": "A::b"}, "hops": []}""")
        val path = project.guessProjectDir()!!.path + "/.codelanes/flows/future.flow.json"
        assertEquals(FlowRead.UnknownVersion(2), FlowFiles.getInstance(project).read(path))
    }

    fun testSlugsAreReadableFileNames() {
        assertEquals("post-orders-id", FlowFiles.slug("POST /orders/{id}"))
        assertEquals("flow", FlowFiles.slug("///"))
    }

    fun testAnotherFlowWithTheSameFileNameGetsItsOwnFile() {
        val files = FlowFiles.getInstance(project)
        val dir = folder("clash")
        val first = files.write(dir, flow.copy(name = "POST /orders"))!!
        val second = files.write(dir, flow.copy(name = "post-orders"))!!
        assertFalse(first == second)
        assertTrue(second, second.endsWith("post-orders-2.flow.json"))
        assertEquals("POST /orders", (files.read(first) as FlowRead.Ok).flow.name)
    }
}
