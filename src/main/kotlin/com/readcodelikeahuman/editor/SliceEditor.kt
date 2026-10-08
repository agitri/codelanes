package com.readcodelikeahuman.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
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
    }

    val component: JComponent get() = editor.component

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
    }

    fun show(range: SourceRange, excluded: List<SourceRange>) {
        bounds?.dispose()
        bounds = document.createRangeMarker(range.start, range.end).apply {
            isGreedyToLeft = false
            isGreedyToRight = true
        }
        val folds = SliceRanges.hidden(document.charsSequence, range, excluded)
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            folding.allFoldRegions.forEach(folding::removeFoldRegion)
            folds.forEach { folding.addFoldRegion(it.start, it.end, "")?.isExpanded = false }
        }
        val caret = editor.caretModel.offset
        if (caret < range.start || caret > range.end) editor.caretModel.moveToOffset(range.start)
    }

    fun setFontSize(size: Int) = editor.setFontSize(size)

    private fun currentFolds(): List<SourceRange> =
        editor.foldingModel.allFoldRegions.filter { it.isValid && !it.isExpanded }.map { SourceRange(it.startOffset, it.endOffset) }

    override fun dispose() {
        bounds?.dispose()
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
