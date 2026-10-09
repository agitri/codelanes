package dev.codelanes.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import dev.codelanes.php.MethodFinder
import dev.codelanes.php.MethodRef

/** "Add method…": search any method in the project and drop it on the canvas that is open. */
class AddMethodToCanvasAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && FileEditorManager.getInstance(project).selectedEditor is BlocksFileEditor
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val canvas = FileEditorManager.getInstance(project).selectedEditor as? BlocksFileEditor ?: return
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Wait until indexing has finished, then try again.", TITLE)
            return
        }
        val methods = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<MethodRef>, RuntimeException>(
            { ReadAction.compute<List<MethodRef>, RuntimeException> { MethodFinder.find(project) } },
            "Finding methods…",
            true,
            project,
        )
        if (methods.isEmpty()) {
            Messages.showInfoMessage(project, "No methods found in this project's own code.", TITLE)
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(methods)
            .setTitle(TITLE)
            .setRenderer(SimpleListCellRenderer.create("") { it.label })
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { canvas.session.addToCanvas(it.classFqn, it.method) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    companion object {
        const val TITLE = "Add Method to Canvas"
    }
}
