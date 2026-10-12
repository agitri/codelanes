package dev.codelanes.editor

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/** Wraps Backspace/Delete so a slice editor can't delete or join text that belongs to a hidden block. */
abstract class SliceDeletionGuard(private val original: EditorActionHandler, private val backward: Boolean) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val slice = editor.getUserData(SliceEditor.KEY)
        // Without a specific caret the action runs at every caret: all of them must be safe.
        val carets = if (caret != null) listOf(caret) else editor.caretModel.allCarets
        if (slice != null && carets.any { !slice.allowsDeletion(it, backward) }) return
        original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        original.isEnabled(editor, caret, dataContext)
}

class SliceBackspaceGuard(original: EditorActionHandler) : SliceDeletionGuard(original, backward = true)

class SliceDeleteGuard(original: EditorActionHandler) : SliceDeletionGuard(original, backward = false)
