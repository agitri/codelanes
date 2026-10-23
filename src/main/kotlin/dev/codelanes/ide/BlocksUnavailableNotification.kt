package dev.codelanes.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.codelanes.lang.LanguageSupports
import dev.codelanes.settings.BlocksSettings
import java.util.function.Function
import javax.swing.JComponent

class BlocksUnavailableNotification : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!BlocksSettings.instance.state.openAsBlocksByDefault) return null
        val language = LanguageSupports.forFile(file) ?: return null
        val reason = ApplicationManager.getApplication().runReadAction<String?> {
            PsiManager.getInstance(project).findFile(file)?.let(language::unsupportedReason)
        } ?: return null
        return Function { editor ->
            if (editor !is TextEditor) null
            else EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply { text = "Blocks view unavailable: $reason" }
        }
    }
}
