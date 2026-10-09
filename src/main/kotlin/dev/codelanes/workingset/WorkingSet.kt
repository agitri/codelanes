package dev.codelanes.workingset

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import java.util.TreeMap

/** One canvas in a working set: which file, and how it was opened up (followed calls, revealed parents, …). */
data class CanvasState(
    val file: String,
    val followedCalls: List<String>,
    val revealed: List<String>,
    val collapsed: Map<String, Boolean>,
    val zoom: Double,
)

/** A named group of canvases for a task or review, e.g. "checkout flow". */
data class WorkingSet(val name: String, val canvases: List<CanvasState>)

/**
 * Working sets, shared in the repository as `.codelanes/working-sets.json` (sorted by name). File paths are stored
 * relative to the project, so the set works on every checkout.
 */
@Service(Service.Level.PROJECT)
class WorkingSetFile(private val project: Project) {
    private class StoredCanvas(
        var file: String? = null,
        var followedCalls: List<String>? = null,
        var revealed: List<String>? = null,
        var collapsed: Map<String, Boolean>? = null,
        var zoom: Double? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val data: TreeMap<String, List<StoredCanvas>> = load()

    fun names(): List<String> = data.keys.toList()

    fun get(name: String): WorkingSet? {
        val canvases = data[name] ?: return null
        return WorkingSet(name, canvases.mapNotNull { stored ->
            val file = stored.file ?: return@mapNotNull null
            CanvasState(
                absolute(file),
                stored.followedCalls.orEmpty(),
                stored.revealed.orEmpty(),
                stored.collapsed.orEmpty(),
                stored.zoom ?: 1.0,
            )
        })
    }

    fun save(set: WorkingSet) {
        data[set.name] = set.canvases.map {
            StoredCanvas(relative(it.file), it.followedCalls.sorted(), it.revealed.sorted(), TreeMap(it.collapsed), it.zoom)
        }
        write()
    }

    fun delete(name: String) {
        if (data.remove(name) != null) write()
    }

    private fun base(): String? = project.guessProjectDir()?.path

    private fun relative(path: String): String = base()?.let { path.removePrefix("$it/") } ?: path

    private fun absolute(path: String): String = if (path.startsWith("/")) path else base()?.let { "$it/$path" } ?: path

    private fun load(): TreeMap<String, List<StoredCanvas>> {
        val file = project.guessProjectDir()?.findFileByRelativePath(PATH) ?: return TreeMap()
        val type = object : TypeToken<TreeMap<String, List<StoredCanvas>>>() {}.type
        return runCatching { gson.fromJson<TreeMap<String, List<StoredCanvas>>>(String(file.contentsToByteArray()), type) }
            .getOrNull() ?: TreeMap()
    }

    private fun write() {
        val dir = project.guessProjectDir() ?: return
        WriteAction.runAndWait<RuntimeException> {
            val folder = VfsUtil.createDirectoryIfMissing(dir, DIRECTORY) ?: return@runAndWait
            val file = folder.findChild(FILE_NAME) ?: folder.createChildData(this, FILE_NAME)
            VfsUtil.saveText(file, gson.toJson(data) + "\n")
        }
    }

    companion object {
        fun getInstance(project: Project): WorkingSetFile = project.getService(WorkingSetFile::class.java)

        const val DIRECTORY = ".codelanes"
        const val FILE_NAME = "working-sets.json"
        const val PATH = "$DIRECTORY/$FILE_NAME"
    }
}
