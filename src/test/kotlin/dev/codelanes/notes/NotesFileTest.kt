package dev.codelanes.notes

import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NotesFileTest : BasePlatformTestCase() {
    fun testNotesAreSavedPerCanvasFileAndReadBack() {
        val canvas = project.guessProjectDir()!!.path + "/src/Flow.php"
        val notes = NotesFile.getInstance(project)
        val id = notes.add(canvas, "Flow is fine, except save()")
        notes.link(canvas, id, "callee:\\App\\Repo::save")
        notes.move(canvas, id, 400, 120)
        val saved = String(project.guessProjectDir()!!.findFileByRelativePath(".codelanes/notes.json")!!.contentsToByteArray())
        assertTrue(saved.contains("src/Flow.php"))
        val note = NotesFile(project).notes(canvas).single { it.id == id }
        assertEquals("Flow is fine, except save()", note.text)
        assertEquals(listOf("callee:\\App\\Repo::save"), note.links)
        assertEquals(400 to 120, note.x to note.y)
    }

    fun testTextCanBeEditedAndNotesDeleted() {
        val canvas = project.guessProjectDir()!!.path + "/src/Edit.php"
        val notes = NotesFile.getInstance(project)
        val id = notes.add(canvas, "draft")
        notes.setText(canvas, id, "final")
        assertEquals("final", notes.notes(canvas).single { it.id == id }.text)
        notes.unlinkAll(canvas, id)
        notes.delete(canvas, id)
        assertTrue(notes.notes(canvas).none { it.id == id })
    }
}
