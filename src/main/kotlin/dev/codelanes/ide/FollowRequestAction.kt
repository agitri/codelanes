package dev.codelanes.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import dev.codelanes.flow.FlowFiles
import dev.codelanes.flow.FlowRead
import dev.codelanes.flow.FlowService
import dev.codelanes.flow.SavedFlow
import dev.codelanes.php.EntryPoint
import dev.codelanes.php.RouteFinder

/**
 * "Follow a Request…": pick a route (e.g. POST /api/orders), open its controller action as a canvas and follow
 * the calls a few levels deep, so the whole chain the request takes is on one canvas. Flows an AI described
 * (`*.flow.json`, see [FlowService]) are listed too, and any flow file can be opened.
 */
class FollowRequestAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Wait until indexing has finished, then try again.", TITLE)
            return
        }
        val routes = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<EntryPoint>, RuntimeException>(
            { ReadAction.nonBlocking<List<EntryPoint>> { RouteFinder.find(project) }.inSmartMode(project).executeSynchronously() },
            "Finding routes…",
            true,
            project,
        )
        val flows = FlowFiles.getInstance(project).list()
        val choices = flows.map { Choice.SavedFlowChoice(it) } + routes.map { Choice.Route(it) } + Choice.OpenFlowFile
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle(if (flows.isEmpty() && routes.isEmpty()) "$TITLE (no #[Route] attributes or saved flows found)" else TITLE)
            .setRenderer(SimpleListCellRenderer.create("") { it.label })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { choice ->
                when (choice) {
                    is Choice.Route -> follow(project, choice.entry)
                    is Choice.SavedFlowChoice -> open(project, choice.saved)
                    Choice.OpenFlowFile -> openFlowFile(project)
                }
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private sealed interface Choice {
        val label: String

        data class Route(val entry: EntryPoint) : Choice {
            override val label get() = entry.label
        }

        data class SavedFlowChoice(val saved: SavedFlow) : Choice {
            override val label get() = "${saved.flow.name}  (flow)"
        }

        data object OpenFlowFile : Choice {
            override val label = "Open flow file…"
        }
    }

    private fun open(project: Project, saved: SavedFlow) {
        FlowService.getInstance(project).open(saved)?.let { Messages.showInfoMessage(project, it, TITLE) }
    }

    /** A flow a teammate saved somewhere else: open it, and remember its folder for next time. */
    private fun openFlowFile(project: Project) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Open Flow File")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val files = FlowFiles.getInstance(project)
        when (val read = files.read(file.path)) {
            is FlowRead.Ok -> {
                files.remember(file.parent.path)
                open(project, SavedFlow(read.flow, file.path))
            }
            FlowRead.Broken -> Messages.showInfoMessage(project, "${file.name} isn't a readable flow file.", TITLE)
            is FlowRead.UnknownVersion -> Messages.showInfoMessage(project, "${file.name} was saved by a newer CodeLanes (format ${read.version}). Update the plugin to open it.", TITLE)
        }
    }

    private fun follow(project: Project, entry: EntryPoint) {
        val file = LocalFileSystem.getInstance().findFileByPath(entry.filePath) ?: return
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        manager.setSelectedEditor(file, BlocksEditorProvider.TYPE_ID)
        val canvas = manager.getSelectedEditor(file) as? BlocksFileEditor
        if (canvas == null) {
            Messages.showInfoMessage(project, "${file.name} can't be shown as blocks, so the chain can't be followed.", TITLE)
            return
        }
        canvas.session.followChain("method:${entry.methodName}", DEPTH)
    }

    companion object {
        const val TITLE = "Follow a Request"
        const val DEPTH = 3
    }
}
