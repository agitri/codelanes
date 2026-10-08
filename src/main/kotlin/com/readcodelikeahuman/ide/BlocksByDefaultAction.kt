package com.readcodelikeahuman.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.EditorNotifications
import com.readcodelikeahuman.settings.BlocksSettings

class BlocksByDefaultAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = BlocksSettings.instance.state.openAsBlocksByDefault

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlocksSettings.instance.state.openAsBlocksByDefault = state
        e.project?.let { EditorNotifications.getInstance(it).updateAllNotifications() }
    }
}
