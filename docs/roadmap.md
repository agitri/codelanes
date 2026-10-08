# Roadmap

Not done yet, in order of priority.

1. **Cmd+click stays on the canvas.** Go to declaration in the same file focuses and expands the target block;
   in another file, that file opens as a canvas.
2. **Usage highlighting.** With the caret on a symbol, every block that uses it gets a coloured border, and the
   defining block a stronger one.
3. **+ method / delete method.** A `+` on the class block creates an empty method block; a block menu deletes one
   (with confirmation).
4. **Keyboard navigation.** Tab between blocks; arrow keys at a block's first/last line move to the neighbouring block.
5. **Reveal deeper parents.** A `+` on a parent/interface block shows its own parents and interfaces.
6. **Rename (Shift+F6) inside a block.** Investigate why it doesn't work and fix it.
7. **Small fixes.**
   - A rebuild during a drag snaps the block back.
   - The toggle shortcut does nothing when the caret is in another file's block.
   - The red notice should be a calm banner.
   - Possible leak if the very first build throws.
   - Corrupt saved positions crash the pin store.
   - Collapsed summaries of other files don't refresh.
8. **Docs.** Fresh README screenshots; publish to the JetBrains Marketplace.
