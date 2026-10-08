# Slice Editor Spike — Findings

## Verdict

**GO WITH WORKAROUNDS** for fold-based slice editors. This is provisional: three manual checklist items (3, 4, 10) are still open and need a person in a running IDE.

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

Not run: the user was away, and there is no GUI access in this session. To run it: `git checkout spike/slice-editor && ./gradlew runIde`, open a PHP class, then **Tools → Spike: Show Slice Editors**.

1. Class slice without methods / method-only slices: covered by automated tests; visual check still open.
2. Typing in a slice changes the real file: **yes** (automated).
3. Completion popup position inside a small embedded editor: **open**.
4. Semantic highlighting / inspections in slices: **open**. This is the biggest unknown. Editors created by `EditorFactory` and not owned by a `FileEditor` may not get daemon highlighting. Lexer syntax colors will work regardless.
5. New lines at the slice end stay visible: **yes** (automated).
6. Undo: **yes** (automated).
7. Caret can't escape: **yes** for programmatic moves (automated). Keyboard Up/Down/Cmd+Home is still to be confirmed by hand.
8. Edits elsewhere keep folds intact: **yes** (automated); visual sync is still to be confirmed.
9. Cmd+click navigation from a slice: **open**.
10. Responsiveness with ~10 slices: **open**.

## Workarounds needed

- **Folds must be re-applied after structural changes.** When a method is added or removed, the class slice's hidden ranges change. The canvas should rebuild folds from the new Block Model after each PSI rebuild, rather than trusting existing fold regions.
- **Slice range tracking:** use a `RangeMarker` per block (greedy to the right) between rebuilds, so typing at the end of a block grows that block.
- **Possible workaround for item 4:** if daemon highlighting is missing, wrap each slice in a `TextEditor` via `TextEditorProvider`, or register the editors with the daemon. Decide once item 4 has been checked by hand.

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

Go with **approach A as designed**: one fold-based slice editor per expanded block. Start the canvas plan by closing manual items 3, 4 and 10. If item 4 (semantic highlighting) fails and the `TextEditor` wrapper doesn't fix it, fall back to **C**: read-only highlighted blocks, where double-clicking makes one block a live slice editor. Fallback C uses the same building blocks.
