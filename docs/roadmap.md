# Roadmap

## Done on `feat/interactions` (needs a manual check in the IDE)

| # | Item | How to check (playground/layers) |
|---|---|---|
| 1 | Cmd+click stays on the canvas | In `Order.php`, Cmd+click `subtotal()` inside `total()`: the `subtotal` block is centred and focused, no text editor. |
| 2 | Usage highlighting | Caret on `total` in `render()`: `total()` gets an orange border, `render()` and `validate()` yellow. |
| 3 | `+ method` / Delete method | Click `+ method` on the class title: `newMethod` appears with its name selected. Right-click a method title → Delete method. |
| 4 | Keyboard navigation | Up on a block's first line / Down on its last line moves into the neighbouring block. |
| 5 | Reveal deeper parents | `+ parents` on `Model` shows `Entity` and `Identifiable`; `− parents` hides them. |
| 6 | Rename in a block | Shift+F6 (fn+Shift+F6 on a Mac) on a method name opens the rename dialog. |
| 7 | Small fixes | Drag isn't snapped back; toggle works with the caret in an interface block; calm notice banner; corrupt pins ignored; other-file summaries refresh. |

## Done recently

- **Follow the flow across files**: `+ calls`.
- **Review notes on blocks**: ✓ / ? / ! marks and notes in `.codelanes/review.json`.
- **Saved working sets**: `.codelanes/working-sets.json`.
- **Follow a request from its entry point**: pick a `#[Route]`, the
  whole chain on one canvas; through interfaces into their implementations.
- **Layouts**: Lanes / Column / Tree, switchable from the canvas toolbar.
- **Search on the canvas**: Add method… drops any project method on the canvas.

## Next

1. **Free-standing note blocks** (built on `feat/note-blocks`, needs an IDE check): `+ Note` on the canvas.
2. **Routes from YAML/XML** (Symfony), not only `#[Route]` attributes.

## Later

- **Record the real road a request took** (long haul): read an Xdebug trace of one real request and show exactly
  the methods that ran, in order (also events and handlers that static analysis can't see).
- **AI picks the relevant chain** (optional): describe a flow in words; AI chooses blocks *only from the real
  call graph*, never invents or explains code.

- **Fresh screenshots** for the README (current ones are from early builds).
- **More languages**: Java/Kotlin, then TypeScript/JavaScript (a new `BlockBuilder` per language; layout and canvas stay).
- **JetBrains Marketplace** release (plugin icon, change notes, signing, `publishPlugin`).
- **VS Code** (later; the slice-editor technique needs a VS Code equivalent).
- Keyboard: Tab-based block switching (Tab is indentation today, so it needs a different shortcut).

## Known small issues (from code review, deferred)

- Synchronous rebuilds after `+ method` / `+ parents` / delete run the full build on the UI thread; may hitch
  on very large projects or widely implemented interfaces.
- Down never leaves an expanded header block (its range ends at the start of the class line).
- With multiple carets, the Backspace/Delete guard only checks the primary caret.
- "Rename" carry-over treats any one-removed/one-added method pair as a rename (also after a git checkout).
- Highlights are scheduled for programmatic caret moves too and aren't cleared when focus leaves the canvas.
- `+ method` uses a 4-space indent instead of the project's code style.
- The same method can carry separate reviews as `method:`, `callee:` or `implementation:` block (one per place it
  appears); a review should follow the method itself.
- `+ calls` silently drops callees beyond 10 (no "and N more" block yet).
- Line labels don't scale with zoom, so at low zoom most of them get a background patch.
- Follow a Request still does a few synchronous rebuilds on the UI thread (bounded by the 80-block cap); one
  background pass would be smoother.
- When a chain can't be followed (syntax errors, indexing) there is only the generic notice, no specific message.
- Tree layout: routing many lines (hundreds of blocks) makes zooming stutter; the toolbar clips on very narrow canvases.
