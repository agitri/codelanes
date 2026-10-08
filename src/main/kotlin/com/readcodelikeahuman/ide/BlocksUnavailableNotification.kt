package com.readcodelikeahuman.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.jetbrains.php.lang.PhpFileType
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.php.PhpBlockBuilder
import com.readcodelikeahuman.settings.BlocksSettings
import java.util.function.Function
import javax.swing.JComponent

class BlocksUnavailableNotification : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!BlocksSettings.instance.state.openAsBlocksByDefault) return null
        if (!FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)) return null
        if (DumbService.isDumb(project)) return null
        val result = ReadAction.compute<BuildResult?, RuntimeException> {
            PsiManager.getInstance(project).findFile(file)?.let(PhpBlockBuilder::build)
        }
        val reason = (result as? BuildResult.Unsupported)?.reason ?: return null
        return Function { editor ->
            if (editor !is TextEditor) null
            else EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply { text = "Blocks view unavailable: $reason" }
        }
    }
}
