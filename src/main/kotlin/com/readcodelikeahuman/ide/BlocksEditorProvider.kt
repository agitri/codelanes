package com.readcodelikeahuman.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.php.lang.PhpFileType
import com.readcodelikeahuman.php.PhpBlockBuilder
import com.readcodelikeahuman.settings.BlocksSettings

/** Cheap structural check only, so it also works (and stays fast) while the IDE is indexing. */
class BlocksEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)) return false
        return ReadAction.compute<Boolean, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute false
            PhpBlockBuilder.unsupportedReason(psi) == null
        }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = BlocksFileEditor(project, file)

    override fun getEditorTypeId(): String = TYPE_ID

    override fun getPolicy(): FileEditorPolicy =
        if (BlocksSettings.instance.state.openAsBlocksByDefault) FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
        else FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID = "read-code-blocks"
    }
}
