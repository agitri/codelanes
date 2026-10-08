# Slice Editor Spike — Findings

## Verdict

**GO WITH WORKAROUNDS** for fold-based slice editors. The manual checklist was completed on 2026-10-08 in the sandbox IDE (see below). Two workarounds are needed, and both belong in the canvas plan.

Everything that could be automated works. That covers folding the rest of the file away, editing the real document, keeping the caret in the slice, completion, undo, and folds surviving edits made elsewhere in the file. The prototype code lives on branch `spike/slice-editor` so the remaining manual checks can be done there.

## Automated tests

Run on PhpStorm 2026.1 (PS-261) with the IntelliJ Platform Gradle Plugin 2.19.0.

- `testSliceFoldsEverythingOutsideRangeAndEditsRealDocument`: **PASS**. 2 collapsed folds. An edit in the slice lands in the real document. A caret move to offset 0 is clamped to the slice start.
- `testClassSliceHidesInnerMethodRanges`: **PASS**. 3 folds: the text before the class plus the 2 hidden methods. There is no trailing fold because the class runs to the end of the file.
- `testCompletionWorksInFoldedEditor`: **PASS**. `$this->` completion inside a folded editor lists `footest`.

Probe test (prints instead of asserting):

- Empty fold placeholders (`""`) are accepted; no fallback to `…` was needed.
- A 3-line method slice of a 10-line file renders as exactly 3 visual lines.
- Text typed right after the slice's closing `}` stays visible: the trailing fold does not swallow it.
- Inserting a new method elsewhere in the document (simulating another block or the normal editor) keeps both folds valid and collapsed.
- Undo is available for the slice editor and reverts the edit.

## Manual checklist

Run by the user in `./gradlew runIde` on 2026-10-08 with `Foo.php` (4 methods) and `Big.php` (10 one-line methods).

1. Class slice without methods / method-only slices: **yes**.
2. Typing in a slice changes the real file: **yes**.
3. Completion popup appears, lists methods, and is positioned correctly: **yes**.
4. Semantic highlighting / inspections in slices: **yes**. The `doesNotExist()` warning shows in the slice.
5. New lines at the slice end stay visible: **yes**.
6. Undo: **yes**.
7. Caret can't escape (Up/Down/Cmd+Home/End): **yes**.
8. Edits in the normal editor update the slices: **yes**.
9. Cmd+click navigation: **works, but jumps to the normal editor** instead of staying on the canvas. This is expected: the spike doesn't intercept navigation. The canvas must handle it (spec: a same-file target focuses that block).
10. Responsiveness with 11 slices: **no lag**. But "ghost typing" was found: see the workarounds below.

## Workarounds needed

- **Holes in the class slice ("ghost typing"), found in check 10.** Folding only the method text leaves the indentation and blank lines around each hidden method visible and editable. Typing in such a hole inserts text right after a method's `}`, on the same physical line. It then shows up glued to the method in its own slice and in the file (`}asdf`). **Fix:** extend each hidden range to whole lines: from the start of the method's first line (or the end of the previous non-whitespace text) through the newline after its last line. If a method shares a line with other code, the class slice must not offer an editable position on that line.
- **Navigation stays on the canvas.** Intercept go-to-declaration (and Cmd+click) inside slice editors. A target inside the same file focuses and scrolls to that block's slice. A target in another file opens that file's canvas.
- **Spike-only bug:** the dialog listed methods in `ownMethods` order (m5, m3, m1…). `PhpBlockBuilder` already sorts methods by offset, so this doesn't affect the real code.

- **Folds must be re-applied after structural changes.** When a method is added or removed, the class slice's hidden ranges change. The canvas should rebuild folds from the new Block Model after each PSI rebuild, rather than trusting existing fold regions.
- **Slice range tracking:** use a `RangeMarker` per block (greedy to the right) between rebuilds, so typing at the end of a block grows that block.

## Technique to carry forward

```kotlin
object SliceEditorSpike {
    fun create(project: Project, file: VirtualFile, document: Document, range: TextRange, hidden: List<TextRange>): EditorEx {
        val editor = EditorFactory.getInstance().createEditor(document, project, file, false) as EditorEx
        editor.settings.apply {
            isFoldingOutlineShown = false
            isLineNumbersShown = false
            additionalLinesCount = 0
            isAdditionalPageAtBottom = false
        }
        applyFolds(editor, range, hidden)

        val marker = document.createRangeMarker(range).apply {
            isGreedyToLeft = false
            isGreedyToRight = true
        }
        editor.caretModel.addCaretListener(object : CaretListener {
            private var clamping = false
            override fun caretPositionChanged(event: CaretEvent) {
                if (clamping || !marker.isValid) return
                val offset = editor.caretModel.offset
                val clamped = offset.coerceIn(marker.startOffset, marker.endOffset)
                if (clamped != offset) {
                    clamping = true
                    try { editor.caretModel.moveToOffset(clamped) } finally { clamping = false }
                }
            }
        })
        editor.caretModel.moveToOffset(range.startOffset)
        return editor
    }

    fun applyFolds(editor: EditorEx, range: TextRange, hidden: List<TextRange>) {
        val length = editor.document.textLength
        val toFold = listOf(TextRange(0, range.startOffset), TextRange(range.endOffset, length)) + hidden
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            toFold.filter { !it.isEmpty }.forEach { r ->
                val region = folding.addFoldRegion(r.startOffset, r.endOffset, "")
                    ?: folding.addFoldRegion(r.startOffset, r.endOffset, "…")
                region?.isExpanded = false
            }
        }
    }
}
```

## Recommendation for the canvas plan

Go with **approach A as designed**: one fold-based slice editor per expanded block. All manual checks passed. The canvas plan must include the whole-line hidden ranges (with a test that typing in the class slice can't land on a method's line) and navigation interception. Fallback C is not needed.
