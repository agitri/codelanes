package com.readcodelikeahuman.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.readcodelikeahuman.model.SourceRange
import javax.swing.JComponent

/** A real editor on the real document, folded down to one block. */
class SliceEditor(project: Project, file: VirtualFile, private val document: Document) : Disposable {
    val editor: EditorEx = (EditorFactory.getInstance().createEditor(document, project, file, false) as EditorEx).apply {
        settings.apply {
            isFoldingOutlineShown = false
            isLineNumbersShown = false
            additionalLinesCount = 0
            isAdditionalPageAtBottom = false
            isCaretRowShown = false
            isRightMarginShown = false
            isUseSoftWraps = false
        }
        setVerticalScrollbarVisible(false)
        scrollPane.isWheelScrollingEnabled = false
    }.also { it.putUserData(KEY, this) }

    val component: JComponent get() = editor.component

    /** True when the block is taller than its cap and scrolls inside. */
    val scrollable: Boolean get() = editor.scrollPane.isWheelScrollingEnabled

    private var bounds: RangeMarker? = null
    private var adjusting = false

    init {
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (adjusting) return
                val marker = bounds?.takeIf { it.isValid } ?: return
                val offset = editor.caretModel.offset
                val target = CaretPolicy.adjust(
                    document.charsSequence,
                    SourceRange(marker.startOffset, marker.endOffset),
                    currentFolds(),
                    offset,
                    editor.logicalPositionToOffset(event.oldPosition),
                )
                if (target != offset) {
                    adjusting = true
                    try { editor.caretModel.moveToOffset(target) } finally { adjusting = false }
                }
            }
        })
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (adjusting) return
                val selection = e.newRange
                if (selection.isEmpty) return
                if (currentFolds().any { it.start < selection.endOffset && it.end > selection.startOffset }) {
                    adjusting = true
                    try { editor.selectionModel.removeSelection() } finally { adjusting = false }
                }
            }
        })
    }

    /** False when Backspace (or Delete) at [caret] would remove or glue on text another block owns. */
    fun allowsDeletion(caret: Caret, backward: Boolean): Boolean {
        val marker = bounds?.takeIf { it.isValid } ?: return true
        val (from, to) = when {
            caret.hasSelection() -> caret.selectionStart to caret.selectionEnd
            backward -> caret.offset - 1 to caret.offset
            else -> caret.offset to caret.offset + 1
        }
        return CaretPolicy.canDelete(document.charsSequence, SourceRange(marker.startOffset, marker.endOffset), currentFolds(), from, to)
    }

    /** Folds the editor down to [range] minus [excluded]; a no-op when nothing would change. */
    fun show(range: SourceRange, excluded: List<SourceRange>) {
        val folds = SliceRanges.hidden(document.charsSequence, range, excluded)
        val current = bounds
        if (current != null && current.isValid && current.startOffset == range.start && current.endOffset == range.end &&
            folds == currentFolds() && editor.foldingModel.allFoldRegions.size == folds.size
        ) return
        bounds?.dispose()
        bounds = document.createRangeMarker(range.start, range.end).apply {
            isGreedyToLeft = false
            isGreedyToRight = true
        }
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            folding.allFoldRegions.forEach(folding::removeFoldRegion)
            folds.forEach { folding.addFoldRegion(it.start, it.end, "")?.isExpanded = false }
        }
        val caret = editor.caretModel.offset
        if (caret < range.start || caret > range.end) editor.caretModel.moveToOffset(range.start)
    }

    fun setFontSize(size: Int) = editor.setFontSize(size)

    /** Lets a block taller than its cap scroll inside instead of panning the canvas. */
    fun setScrollable(scrollable: Boolean) {
        editor.setVerticalScrollbarVisible(scrollable)
        editor.scrollPane.isWheelScrollingEnabled = scrollable
    }

    private fun currentFolds(): List<SourceRange> =
        editor.foldingModel.allFoldRegions.filter { it.isValid && !it.isExpanded }.map { SourceRange(it.startOffset, it.endOffset) }

    companion object {
        /** Marks an editor as a slice so editor action guards can find it. */
        val KEY: Key<SliceEditor> = Key.create("readcode.sliceEditor")
    }

    override fun dispose() {
        bounds?.dispose()
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
