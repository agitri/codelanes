package com.readcodelikeahuman.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.readcodelikeahuman.canvas.BlocksSession
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class BlocksFileEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
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
    override fun dispose() {}
}
