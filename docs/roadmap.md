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
- **Note blocks**: `+ Note`, linked to blocks, shared in `.codelanes/notes.json`.

## Next

1. **Routes from YAML/XML** (built on `feat/route-files`, needs an IDE check): `config/routes` files in Follow a Request.

## Later (in order)

1. **Polish from the reviews** (done for 0.1.0; leftovers below).
2. **JetBrains Marketplace release 0.1.0**: icon, description, change notes, signing and publishing config are ready and `verifyPlugin` passes on PhpStorm 2025.1 and 2026.1. Left: create the signing key and token, then `./gradlew publishPlugin`.
3. **Fresh screenshots** for the README and the Marketplace page.
4. **Java/Kotlin**: a new `BlockBuilder` per language; layout, canvas, reviews and notes stay. The biggest group of
   JetBrains users.
5. **TypeScript/JavaScript**.
6. **Record the real road a request took** (long haul): read an Xdebug trace of one real request and show exactly
   the methods that ran, in order (also events and handlers that static analysis can't see).
7. **AI picks the relevant chain** (optional): describe a flow in words; AI chooses blocks *only from the real
   call graph*, never invents or explains code.
8. **VS Code**: needs its own equivalent of the slice editors.
9. Keyboard: Tab-based block switching (Tab is indentation today, so it needs a different shortcut).

## Known small issues (from code review, deferred)

- Synchronous rebuilds after `+ method` / `+ parents` / delete / Follow a Request run on the UI thread; bounded
  (80-block cap) but can hitch on very large projects or widely implemented interfaces.
- Tree layout: routing many lines (hundreds of blocks) makes zooming stutter.
- A class reviewed in its own canvas and as a parent/interface block elsewhere still has separate reviews
  (methods already share one review wherever they appear).
