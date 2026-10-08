package dev.codelanes.ide

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.NavigatableFileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.pom.Navigatable
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import dev.codelanes.canvas.BlocksSession
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class BlocksFileEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), NavigatableFileEditor {
    internal val session = BlocksSession(project, file).also { Disposer.register(this, it) }

    override fun getComponent(): JComponent = session.canvas
    override fun getPreferredFocusedComponent(): JComponent = session.canvas
    override fun getName(): String = "Blocks"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    /** Go-to-declaration into this file stays on the canvas instead of switching to the text editor. */
    override fun canNavigateTo(navigatable: Navigatable): Boolean =
        navigatable is OpenFileDescriptor && navigatable.file == file &&
            offsetOf(navigatable)?.let { session.blockAt(it) } != null

    override fun navigateTo(navigatable: Navigatable) {
        val descriptor = navigatable as? OpenFileDescriptor ?: return
        offsetOf(descriptor)?.let(session::reveal)
    }

    private fun offsetOf(descriptor: OpenFileDescriptor): Int? {
        if (descriptor.offset >= 0) return descriptor.offset
        if (descriptor.line < 0) return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (descriptor.line >= document.lineCount) return null
        return document.getLineStartOffset(descriptor.line) + maxOf(descriptor.column, 0)
    }

    override fun dispose() {}
}
