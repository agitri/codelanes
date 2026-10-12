package dev.codelanes.flow

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Flows, one dot file each (`post-orders.flow.json`). They live in `.codelanes/flows/` (shared through git) or in any
 * folder the user picked when saving; those folders are remembered per user, in the workspace file.
 * A file that can't be parsed, or comes from a newer CodeLanes, is never overwritten.
 */
@Service(Service.Level.PROJECT)
@State(name = "CodeLanesFlowFolders", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class FlowFiles(private val project: Project) : SimplePersistentStateComponent<FlowFiles.FoldersState>(FoldersState()) {
    class FoldersState : BaseState() {
        var folders by list<String>()
    }

    private class StoredFlow(
        var version: Int? = null,
        var name: String? = null,
        var entry: StoredEntry? = null,
        var hops: MutableList<StoredHop>? = null,
    )

    private class StoredEntry(var label: String? = null, var method: String? = null)
    private class StoredHop(var from: String? = null, var to: String? = null, var kind: String? = null)

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Every readable flow in `.codelanes/flows/` and the remembered folders, by name. */
    fun list(): List<SavedFlow> =
        folders().mapNotNull(::findFile).filter { it.isDirectory }
            // Flows written outside the IDE (an AI in a terminal, git pull) must be seen too.
            .onEach { if (!ApplicationManager.getApplication().isReadAccessAllowed) it.refresh(false, false) }
            .flatMap { dir -> dir.children.filter { it.name.endsWith(SUFFIX) } }
            .mapNotNull { file -> (read(file.path) as? FlowRead.Ok)?.let { SavedFlow(it.flow, file.path) } }
            .distinctBy { it.path }
            .sortedBy { it.flow.name }

    fun find(name: String): SavedFlow? = list().firstOrNull { it.flow.name == name }

    fun read(path: String): FlowRead {
        val file = findFile(path) ?: return FlowRead.Broken
        val text = runCatching { String(file.contentsToByteArray()) }.getOrNull() ?: return FlowRead.Broken
        val version = runCatching { JsonParser.parseString(text).asJsonObject.get("version")?.asInt }.getOrNull()
            ?: return FlowRead.Broken
        if (version != VERSION) return FlowRead.UnknownVersion(version)
        val stored = runCatching { gson.fromJson(text, StoredFlow::class.java) }.getOrNull() ?: return FlowRead.Broken
        val entry = stored.entry ?: return FlowRead.Broken
        return FlowRead.Ok(
            Flow(
                stored.name ?: return FlowRead.Broken,
                FlowEntry(entry.label.orEmpty(), entry.method ?: return FlowRead.Broken),
                stored.hops.orEmpty().mapNotNull { hop -> FlowHop(hop.from ?: return@mapNotNull null, hop.to ?: return@mapNotNull null, hop.kind.orEmpty()) },
            ),
        )
    }

    /**
     * Saves [flow] as a new file in [folder] and remembers the folder. Returns the path, or null if it couldn't.
     * Another flow whose name gives the same file name keeps its file; this one gets `-2`, `-3`, …
     * Throws [java.io.IOException] when the folder can't be written.
     */
    fun write(folder: VirtualFile, flow: Flow): String? {
        val base = slug(flow.name)
        val name = generateSequence(1) { it + 1 }.map { n -> (if (n == 1) base else "$base-$n") + SUFFIX }.first { candidate ->
            val existing = folder.findChild(candidate) ?: return@first true
            when (val read = read(existing.path)) {
                is FlowRead.Ok -> read.flow.name == flow.name // the same flow: replace it; another one: try the next name
                else -> true // unreadable: stop here and refuse below, it may be this very flow in a merge conflict
            }
        }
        val existing = folder.findChild(name)
        if (existing != null && read(existing.path) !is FlowRead.Ok) return null
        val path = save(folder, name, flow) ?: return null
        remember(folder.path)
        return path
    }

    /** Replaces the flow in [path] (same name, asked again). False, and nothing written, if that file isn't a readable flow. */
    fun overwrite(path: String, flow: Flow): Boolean {
        if (read(path) !is FlowRead.Ok) return false
        val file = findFile(path) ?: return false
        return save(file.parent, file.name, flow) != null
    }

    /** `.codelanes/flows/` plus every folder this user saved or opened a flow in. */
    fun folders(): List<String> =
        (listOfNotNull(project.guessProjectDir()?.path?.let { "$it/$DEFAULT_FOLDER" }) + state.folders).distinct()

    fun remember(folderPath: String) {
        if (folderPath in folders()) return
        state.folders = (state.folders + folderPath).toMutableList()
    }

    private fun save(folder: VirtualFile, name: String, flow: Flow): String? = WriteAction.computeAndWait<String?, RuntimeException> {
        val stored = StoredFlow(
            VERSION, flow.name, StoredEntry(flow.entry.label, flow.entry.method),
            flow.hops.map { StoredHop(it.from, it.to, it.kind) }.toMutableList(),
        )
        val file = folder.findChild(name) ?: folder.createChildData(this, name)
        VfsUtil.saveText(file, gson.toJson(stored) + "\n")
        file.path
    }

    private fun findFile(path: String): VirtualFile? =
        (project.guessProjectDir()?.fileSystem ?: return null).findFileByPath(path)

    companion object {
        fun getInstance(project: Project): FlowFiles = project.getService(FlowFiles::class.java)

        const val DEFAULT_FOLDER = ".codelanes/flows"
        const val SUFFIX = ".flow.json"
        const val VERSION = 1

        /** "POST /orders/{id}" → "post-orders-id". */
        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "flow" }
    }
}
