package com.readcodelikeahuman.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.php.lang.PhpFileType
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.php.PhpBlockBuilder
import com.readcodelikeahuman.settings.BlocksSettings

class BlocksEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)) return false
        return ReadAction.compute<Boolean, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute false
            PhpBlockBuilder.build(psi) is BuildResult.Supported
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
