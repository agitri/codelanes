package com.readcodelikeahuman.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware

/** The hidden toggle: switch the current PHP file between the blocks canvas and the classic text editor. */
class ToggleBlocksViewAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val manager = FileEditorManager.getInstance(project)
        val target = if (manager.getSelectedEditor(file) is BlocksFileEditor) "text-editor" else BlocksEditorProvider.TYPE_ID
        manager.setSelectedEditor(file, target)
    }
}
