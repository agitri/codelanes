package dev.codelanes.notes

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.codelanes.shared.SharedJsonFile
import java.util.TreeMap
import java.util.UUID

/** A free-standing note on a canvas: text, the blocks it points at, and where it sits (null = not placed yet). */
data class Note(val id: String, val text: String, val links: List<String>, val x: Int?, val y: Int?)

/**
 * Notes, shared in the repository as `.codelanes/notes.json`, per canvas file (relative path). Re-read after a git
 * pull and never overwritten when it can't be parsed (see [SharedJsonFile]).
 */
@Service(Service.Level.PROJECT)
class NotesFile(project: Project) {
    private class StoredNote(
        var id: String? = null,
        var text: String? = null,
        var links: MutableList<String>? = null,
        var x: Int? = null,
        var y: Int? = null,
    )

    private val file = SharedJsonFile<TreeMap<String, MutableList<StoredNote>>>(
        project, PATH, object : TypeToken<TreeMap<String, MutableList<StoredNote>>>() {}.type, { TreeMap() },
    )

    fun notes(canvasFile: String): List<Note> {
        val key = file.relative(canvasFile) ?: return emptyList()
        return file.read()[key].orEmpty().mapNotNull { stored ->
            Note(stored.id ?: return@mapNotNull null, stored.text.orEmpty(), stored.links.orEmpty().toList(), stored.x, stored.y)
        }
    }

    /** Adds a note to [canvasFile] and returns its id ("" if it couldn't be stored). */
    fun add(canvasFile: String, text: String): String {
        val key = file.relative(canvasFile) ?: return ""
        val id = UUID.randomUUID().toString().take(8)
        val stored = file.update { data -> data.getOrPut(key) { mutableListOf() } += StoredNote(id, text, mutableListOf()) }
        return if (stored) id else ""
    }

    fun setText(canvasFile: String, id: String, text: String) = change(canvasFile, id) { it.text = text }

    fun link(canvasFile: String, id: String, blockId: String) = change(canvasFile, id) { note ->
        val links = note.links ?: mutableListOf<String>().also { note.links = it }
        if (blockId !in links) links += blockId
    }

    fun unlinkAll(canvasFile: String, id: String) = change(canvasFile, id) { it.links = mutableListOf() }

    fun move(canvasFile: String, id: String, x: Int, y: Int) = change(canvasFile, id) {
        it.x = x
        it.y = y
    }

    fun delete(canvasFile: String, id: String) {
        val key = file.relative(canvasFile) ?: return
        file.update { data ->
            data[key]?.removeIf { it.id == id }
            if (data[key]?.isEmpty() == true) data.remove(key)
        }
    }

    private fun change(canvasFile: String, id: String, edit: (StoredNote) -> Unit) {
        val key = file.relative(canvasFile) ?: return
        file.update { data -> data[key]?.firstOrNull { it.id == id }?.let(edit) }
    }

    companion object {
        fun getInstance(project: Project): NotesFile = project.getService(NotesFile::class.java)

        const val PATH = ".codelanes/notes.json"
    }
}
