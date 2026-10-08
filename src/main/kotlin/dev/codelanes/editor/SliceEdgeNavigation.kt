package dev.codelanes.editor

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/** Up on a block's first line / Down on its last line moves into the neighbouring block instead of stopping. */
abstract class SliceEdgeNavigation(private val original: EditorActionHandler, private val up: Boolean) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val slice = editor.getUserData(SliceEditor.KEY)
        val current = caret ?: editor.caretModel.currentCaret
        // With a completion popup open, Up/Down belong to the popup.
        val popupOpen = com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(editor) != null
        if (slice != null && !popupOpen && !current.hasSelection() && slice.atEdge(up) && slice.onEdge?.invoke(up) == true) return
        original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        original.isEnabled(editor, caret, dataContext)
}

class SliceUpNavigation(original: EditorActionHandler) : SliceEdgeNavigation(original, up = true)

class SliceDownNavigation(original: EditorActionHandler) : SliceEdgeNavigation(original, up = false)
