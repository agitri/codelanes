package dev.codelanes.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.ProjectManager
import dev.codelanes.settings.BlocksSettings

/** Experiment: every block in one column, top to bottom, instead of lanes. */
class VerticalLayoutAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = BlocksSettings.instance.state.verticalLayout

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlocksSettings.instance.state.verticalLayout = state
        for (project in ProjectManager.getInstance().openProjects) {
            FileEditorManager.getInstance(project).allEditors.filterIsInstance<BlocksFileEditor>().forEach { it.session.refreshLayout() }
        }
    }
}
