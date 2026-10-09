package dev.codelanes.workingset

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.codelanes.shared.SharedJsonFile
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
class WorkingSetFile(project: Project) {
    private class StoredCanvas(
        var file: String? = null,
        var followedCalls: List<String>? = null,
        var revealed: List<String>? = null,
        var collapsed: Map<String, Boolean>? = null,
        var zoom: Double? = null,
    )

    private val file = SharedJsonFile<TreeMap<String, List<StoredCanvas>>>(
        project, PATH, object : TypeToken<TreeMap<String, List<StoredCanvas>>>() {}.type, { TreeMap() },
    )

    fun names(): List<String> = file.read().keys.toList()

    fun exists(name: String): Boolean = name in file.read()

    fun get(name: String): WorkingSet? {
        val canvases = file.read()[name] ?: return null
        return WorkingSet(name, canvases.mapNotNull { stored ->
            val path = stored.file ?: return@mapNotNull null
            CanvasState(
                file.absolute(path),
                stored.followedCalls.orEmpty(),
                stored.revealed.orEmpty(),
                stored.collapsed.orEmpty(),
                stored.zoom ?: 1.0,
            )
        })
    }

    /** False when nothing was written (working-sets.json can't be read). */
    fun save(set: WorkingSet): Boolean = file.update { data ->
        data[set.name] = set.canvases.distinctBy { it.file }.map {
            StoredCanvas(file.relative(it.file) ?: it.file, it.followedCalls.sorted(), it.revealed.sorted(), TreeMap(it.collapsed), it.zoom)
        }
    }

    fun delete(name: String): Boolean = file.update { data -> data.remove(name) }

    companion object {
        fun getInstance(project: Project): WorkingSetFile = project.getService(WorkingSetFile::class.java)

        const val PATH = ".codelanes/working-sets.json"
    }
}
