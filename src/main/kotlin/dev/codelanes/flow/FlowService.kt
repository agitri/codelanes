package dev.codelanes.flow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.codelanes.ide.BlocksEditorProvider
import dev.codelanes.ide.BlocksFileEditor

/** Checks what an AI sent before anything is resolved: a name, readable hops, and only the hop kinds CodeLanes knows. */
object FlowInput {
    data class Checked(val flow: Flow?, val error: String?)

    private val ARROW = Regex("""^\s*(\S+)\s*--\s*([A-Za-z]+)\s*-->\s*(\S+)\s*$""")

    /** `App\A::b --dispatches--> App\C::d` → a hop; null if it isn't written like that. */
    fun parseHop(text: String): FlowHop? =
        ARROW.matchEntire(text)?.destructured?.let { (from, kind, to) -> FlowHop(from, to, kind) }

    fun check(name: String, entryLabel: String, entryMethod: String, hops: List<FlowHop>, hopTexts: List<String> = emptyList()): Checked {
        if (name.isBlank()) return Checked(null, "The flow needs a name, e.g. \"POST /orders\".")
        if (entryMethod.isBlank()) return Checked(null, "The flow needs an entry method, e.g. \"App\\Http\\Controllers\\OrderController::store\".")
        val unreadable = hopTexts.filter { parseHop(it) == null }
        if (unreadable.isNotEmpty()) {
            return Checked(null, "Can't read ${unreadable.joinToString(", ") { "\"$it\"" }}. Write each hop as From\\Class::method --kind--> To\\Class::method.")
        }
        val all = hops + hopTexts.mapNotNull(::parseHop)
        val bad = all.map { it.kind }.filter { it !in Flow.HOP_KINDS }.distinct()
        if (bad.isNotEmpty()) {
            return Checked(null, "Unknown hop kind ${bad.joinToString(", ") { "\"$it\"" }}. Use one of: ${Flow.HOP_KINDS.joinToString(", ")}.")
        }
        val flow = Flow(name.trim(), FlowEntry(entryLabel.trim().ifEmpty { name.trim() }, entryMethod.trim()), all.map { it.copy(from = it.from.trim(), to = it.to.trim()) })
        return Checked(flow, null)
    }
}

/**
 * Shows a flow on the canvas of its entry method and saves it: a known flow (same name) is updated in place, a new
 * one goes where the user chooses. Runs on the EDT. Returns the reply for the AI.
 */
@Service(Service.Level.PROJECT)
class FlowService(private val project: Project) {
    /** Asks where to save a new flow; null when the user cancels. Replaced in tests. */
    var chooseFolder: (suggested: VirtualFile?) -> VirtualFile? = { suggested ->
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Where Should CodeLanes Save This Flow?")
            .withDescription("Pick .codelanes/flows to share it with your team through git, or a git-ignored folder to keep it to yourself.")
        FileChooser.chooseFile(descriptor, project, suggested)
    }

    /** Opens [file] as blocks and shows the flow there; false if it can't be shown as blocks. Replaced in tests. */
    var showOnCanvas: (file: VirtualFile, resolved: ResolvedFlow, title: String) -> Boolean = { file, resolved, title ->
        val manager = FileEditorManager.getInstance(project)
        manager.openFileEditor(OpenFileDescriptor(project, file), true)
        manager.setSelectedEditor(file, BlocksEditorProvider.TYPE_ID)
        val canvas = manager.getSelectedEditor(file) as? BlocksFileEditor
        canvas?.session?.showFlow(resolved, title)
        canvas != null
    }

    /** [resolved] can come from a background read action (the MCP tool does that); resolving here blocks the EDT. */
    fun show(flow: Flow, resolved: ResolvedFlow? = null): String {
        if (resolved == null && DumbService.isDumb(project)) return "Nothing shown: the IDE is still indexing. Try again when it has finished."
        val resolved = resolved ?: resolve(flow)
        resolved.entryProblem?.let { return "Nothing shown: the entry $it." }
        if (!showOnCanvas(resolved.entryFile!!, resolved, flow.entry.label)) {
            return "Nothing shown: ${resolved.entryFile.name} can't be opened as blocks."
        }
        val proven = resolved.hops.count { it.proven }
        val reply = StringBuilder("Shown \"${flow.name}\": ${resolved.hops.size} hops, $proven proven, ${resolved.hops.size - proven} claimed.")
        val saved = try {
            save(flow)
        } catch (e: java.io.IOException) {
            "Not saved: ${e.message ?: "the file couldn't be written"}."
        }
        reply.append(' ').append(saved)
        if (resolved.unknown.isNotEmpty()) reply.append(" Unknown, not drawn: ${resolved.unknown.joinToString("; ")}.")
        return reply.toString()
    }

    /** Opens a saved flow (Follow a Request); an error text if it can't be shown. */
    fun open(saved: SavedFlow, resolved: ResolvedFlow? = null): String? {
        if (resolved == null && DumbService.isDumb(project)) return "Wait until indexing has finished, then try again."
        val resolved = resolved ?: resolve(saved.flow)
        resolved.entryProblem?.let { return "Couldn't show the flow: the entry $it." }
        return if (showOnCanvas(resolved.entryFile!!, resolved, saved.flow.entry.label)) null
        else "${resolved.entryFile.name} can't be shown as blocks."
    }

    private fun resolve(flow: Flow): ResolvedFlow =
        ApplicationManager.getApplication().runReadAction<ResolvedFlow> { FlowResolver.resolve(project, flow) }

    private fun save(flow: Flow): String {
        val files = FlowFiles.getInstance(project)
        files.find(flow.name)?.let { known ->
            return if (files.overwrite(known.path, flow)) "Updated ${shown(known.path)}."
            else "Not saved: ${shown(known.path)} can't be read (merge conflict?), so it was left alone."
        }
        val folder = chooseFolder(suggestedFolder()) ?: return "Not saved: the user cancelled choosing a folder."
        val path = files.write(folder, flow) ?: return "Not saved: ${shown(folder.path)} already has a file for this flow that can't be read."
        return "Saved to ${shown(path)}."
    }

    private fun suggestedFolder(): VirtualFile? {
        val base = project.guessProjectDir() ?: return null
        return WriteAction.computeAndWait<VirtualFile?, RuntimeException> { VfsUtil.createDirectoryIfMissing(base, FlowFiles.DEFAULT_FOLDER) }
    }

    private fun shown(path: String): String {
        val base = project.guessProjectDir()?.path ?: return path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    companion object {
        fun getInstance(project: Project): FlowService = project.getService(FlowService::class.java)
    }
}
