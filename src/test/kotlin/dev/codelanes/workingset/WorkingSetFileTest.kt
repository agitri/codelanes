package dev.codelanes.workingset

import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WorkingSetFileTest : BasePlatformTestCase() {
    private fun canvas(path: String) = CanvasState(
        file = path,
        followedCalls = listOf("method:render"),
        revealed = listOf("parent:\\App\\Model"),
        collapsed = mapOf("method:total" to true),
        zoom = 0.8,
    )

    fun testASavedWorkingSetIsStoredInTheRepoAndReadBack() {
        val base = project.guessProjectDir()!!.path
        WorkingSetFile(project).save(WorkingSet("checkout flow", listOf(canvas("$base/src/Order.php"))))
        val stored = project.guessProjectDir()!!.findFileByRelativePath(".codelanes/working-sets.json")
        assertNotNull(stored)
        val text = String(stored!!.contentsToByteArray())
        assertTrue(text.contains("checkout flow"))
        assertTrue("paths are stored relative to the project: $text", text.contains("\"src/Order.php\""))
        val back = WorkingSetFile(project).get("checkout flow")!!
        assertEquals(listOf(canvas("$base/src/Order.php")), back.canvases)
    }

    fun testNamesAreSortedAndSetsCanBeDeleted() {
        val file = WorkingSetFile(project)
        file.save(WorkingSet("zeta", emptyList()))
        file.save(WorkingSet("alpha", emptyList()))
        assertEquals(listOf("alpha", "zeta"), file.names().filter { it == "alpha" || it == "zeta" })
        file.delete("zeta")
        assertFalse(WorkingSetFile(project).names().contains("zeta"))
    }
}
