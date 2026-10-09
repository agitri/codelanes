package dev.codelanes.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import dev.codelanes.workingset.WorkingSet
import dev.codelanes.workingset.WorkingSetFile

private fun openCanvases(project: Project): List<BlocksFileEditor> =
    FileEditorManager.getInstance(project).allEditors.filterIsInstance<BlocksFileEditor>().distinctBy { it.file }

/** Lets the user pick a saved working set by name, then runs [onChosen]. */
private fun chooseWorkingSet(project: Project, title: String, onChosen: (String) -> Unit) {
    val names = WorkingSetFile.getInstance(project).names()
    if (names.isEmpty()) {
        Messages.showInfoMessage(project, "There are no working sets yet. Use Save Working Set… first.", title)
        return
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(names)
        .setTitle(title)
        .setItemChosenCallback(onChosen)
        .createPopup()
        .showCenteredInCurrentWindow(project)
}

/** Saves every open canvas (and how it is opened up) under a name, in .codelanes/working-sets.json. */
class SaveWorkingSetAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val canvases = openCanvases(project)
        if (canvases.isEmpty()) {
            Messages.showInfoMessage(project, "Open a file as blocks first.", "Save Working Set")
            return
        }
        val name = Messages.showInputDialog(project, "Name for this working set:", "Save Working Set", null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val sets = WorkingSetFile.getInstance(project)
        if (sets.exists(name) &&
            Messages.showYesNoDialog(project, "Replace working set \"$name\"?", "Save Working Set", null) != Messages.YES
        ) return
        sets.save(WorkingSet(name, canvases.map { it.session.snapshot() }))
    }
}

/** Reopens the canvases of a working set, each restored to how it was saved. */
class OpenWorkingSetAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorkingSet(project, "Open Working Set") { name ->
            val set = WorkingSetFile.getInstance(project).get(name) ?: return@chooseWorkingSet
            val manager = FileEditorManager.getInstance(project)
            val missing = mutableListOf<String>()
            for (state in set.canvases) {
                val file = LocalFileSystem.getInstance().findFileByPath(state.file)
                if (file == null) {
                    missing += state.file.substringAfterLast('/')
                    continue
                }
                manager.openFile(file, true)
                manager.setSelectedEditor(file, BlocksEditorProvider.TYPE_ID)
                val canvas = manager.getSelectedEditor(file) as? BlocksFileEditor
                if (canvas == null) missing += file.name else canvas.session.restore(state)
            }
            if (missing.isNotEmpty()) {
                Messages.showWarningDialog(
                    project,
                    "Couldn't restore ${missing.size} canvas(es): ${missing.joinToString(", ")} (moved, deleted or no longer shown as blocks).",
                    "Open Working Set",
                )
            }
        }
    }
}

class DeleteWorkingSetAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorkingSet(project, "Delete Working Set") { name ->
            if (Messages.showYesNoDialog(project, "Delete working set \"$name\"?", "Delete Working Set", null) == Messages.YES) {
                WorkingSetFile.getInstance(project).delete(name)
            }
        }
    }
}
