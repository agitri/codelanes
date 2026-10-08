package dev.codelanes.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware

/** Drops all dragged positions of the blocks file that is currently shown. */
class ResetBlocksLayoutAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectedBlocksEditor(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        selectedBlocksEditor(e)?.session?.resetLayout()
    }

    private fun selectedBlocksEditor(e: AnActionEvent): BlocksFileEditor? {
        val project = e.project ?: return null
        return FileEditorManager.getInstance(project).selectedEditor as? BlocksFileEditor
    }
}
