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
import dev.codelanes.php.EntryPoint
import dev.codelanes.php.RouteFinder

/**
 * "Follow a Request…": pick a route (e.g. POST /api/orders), open its controller action as a canvas and follow
 * the calls a few levels deep, so the whole chain the request takes is on one canvas.
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
            { ReadAction.compute<List<EntryPoint>, RuntimeException> { RouteFinder.find(project) } },
            "Finding routes…",
            true,
            project,
        )
        if (routes.isEmpty()) {
            Messages.showInfoMessage(project, "No #[Route] attributes found in this project.", TITLE)
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(routes)
            .setTitle(TITLE)
            .setRenderer(SimpleListCellRenderer.create("") { it.label })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { follow(project, it) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
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
