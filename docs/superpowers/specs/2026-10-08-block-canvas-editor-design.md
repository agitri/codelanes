# Block Canvas Editor — Design

**Date:** 2026-10-08
**Status:** Draft for review

## Purpose

AI writes more and more code that humans skim instead of understand. This plugin
replaces the traditional vertical, line-by-line editor with a 2D canvas of
editable **blocks**: a class is a block, its methods are blocks, and related
types are blocks — connected by arrows. Code is cut into digestible chunks so a
developer actually reads and understands what was written.

It is a **viewer and editor**, not an explainer: no AI, no summaries, no
learning mode in v1. Blocks are derived purely from the real code.

## Scope

**v1:** JetBrains plugin (PhpStorm), PHP only, files containing exactly one
class / interface / trait / enum.

**Later (in order):** Java/Kotlin, TypeScript/JavaScript, then possibly VS Code
and terminal. Explanations / junior mode possibly later.

**Not in v1:** other languages, multi-class or procedural files as blocks,
user-draggable layouts, explanations, VS Code, terminal.

## User experience

- The block canvas **is** the editor for supported PHP files. It replaces the
  text editor; it is not a side panel or preview.
- A setting enables it as the default for all supported files ("set view to
  blocks"). A command / button opens a given file as blocks.
- A hidden toggle (shortcut / command) switches a file to the classic text view.
- Unsupported files open in the classic text editor automatically, with a small
  notice saying why.

### Canvas layout (automatic, deterministic)

```
                     [ file header: namespace + use imports ]   (collapsed)

[ interface Barro ] ──►  ┌─ class Foo implements Barro ─┐ ──► [ method bar()      ]
[ parent class    ] ──►  │ properties                   │
[ trait X         ] ──►  │ constants                    │ ──► [ method footest()  ]
[ dep UserRepo    ] ──►  │ constructor                  │
                         └──────────────────────────────┘
```

- **Left column:** implemented interfaces, parent class, used traits,
  constructor-injected dependencies.
- **Center:** file header block (namespace + `use` imports, collapsed by
  default, editable when expanded) above the class block (signature,
  properties, constants, constructor).
- **Right column:** one block per method, in source order.
- Interface, trait and enum files use the same layout: that type sits in the
  center block (enum cases count as constants), with its methods or abstract
  signatures on the right.
- Layout is computed automatically only; the same file always looks the same.

### Arrows

- Left → class: `implements`, `extends`, `uses` (trait), `injects` — each with
  its own line style.
- Class → methods: `owns`.
- Method → method: `calls` (e.g. `$this->footest()`), drawn only for the focused
  block to keep the canvas calm.

### Blocks from other files

Interfaces, parents, traits and dependencies live in other files. They appear
**collapsed** (name + signatures). Clicking expands the full code inline; it is
editable and edits that other file.

Reference resolution is delegated entirely to the IDE (PSI references). If the
IDE resolves a reference, it gets a block. If not, no block is created and the
name keeps the IDE's standard unresolved styling inside its block. No custom
resolution logic.

### Usage highlighting

When the caret is on a symbol (a `use` import, `new TestCodeClass`, a type
hint, `$this->repo`, a method name), every block containing a usage of that
symbol gets a highlighted border; the block defining it gets a stronger border.
Works across all columns. Uses the IDE's own usage search, not text matching.
Updates as the caret moves.

### Editing

- Each block contains a real IntelliJ editor bound to that block's text range in
  the real document (a "slice editor"). Completion, inspections, refactorings,
  undo and shortcuts work as normal.
- **Add method:** a `+ method` button on the class block creates an empty method
  block. Typing a complete function inside the class block also works: after
  re-parse it pops out into its own block.
- **Delete method:** via block menu or shortcut, with confirmation.

### Navigation and keyboard

- Click or Tab moves focus between blocks; arrow keys at a block's edge move to
  the adjacent block.
- Go-to-declaration to a symbol in the same file focuses and scrolls to that
  block; to another file, opens that file's canvas.

## Architecture

Four units, each with one job:

1. **Block Model** (language-agnostic data)
   - `Block`: kind (`header | interface | parent | trait | dependency | class | method`),
     source file, text range, collapsed state.
   - `Link`: kind (`implements | extends | uses | injects | owns | calls`), from, to.
   - No knowledge of PHP or rendering. Future languages produce the same model.

2. **PHP Block Builder** (one per language)
   - Input: PHP PSI file. Output: Block Model, or `Unsupported(reason)`.
   - Extracts header, class block, methods, interfaces/parent/traits (resolved
     via PSI to their files), constructor-injected dependencies, and
     intra-class method calls.

3. **Layout Engine**
   - Input: Block Model. Output: block positions and arrow routes.
   - Pure and deterministic; three-column rule above.

4. **Canvas Editor** (JetBrains UI)
   - A `FileEditorProvider` registered for PHP files that replaces the default
     text editor when enabled.
   - Swing canvas with pan/zoom; renders blocks, arrows, highlight borders.
   - Hosts one slice editor per expanded block.
   - Provides the toggle to the classic text view.

### Data flow

Type in a block → real `Document` changes → PSI re-parses → Builder rebuilds the
model → canvas diffs and updates only what changed (new method appears, removed
method disappears, signature changes). The file on disk is the single source of
truth; the canvas never stores code itself. External changes (git checkout, an
AI agent writing the file) follow the same path.

## Error handling and edge cases

- **Unparseable while typing:** keep the last good layout; the block being edited
  stays in place. Rebuild once the PSI is valid again. No jumping blocks.
- **Unsupported files:** fall back to the text editor with a notice.
- **Unresolved references:** handled by the IDE as described above; no extra
  logic.
- **Very large classes (50+ methods):** v1 simply stacks them in the right
  column; optimize later if needed.

## Technology

- Kotlin, IntelliJ Platform Gradle Plugin.
- Target PhpStorm 2025.x+, depending on the PHP plugin.
- Swing UI that follows the IDE theme (light/dark).
- Fully local; no network, no AI.

## Key risk and first step

**Risk:** a real IntelliJ editor that shows and edits only a range of a
document, with completion and inspections still working, is not a standard
platform feature.

**First step (throwaway spike):** prove that a slice editor can display one
method of a PHP file, keep PHP completion working, and write edits back to the
right lines of the real file. If it works, proceed with this design. If not,
fall back to read-only highlighted blocks where double-click turns one block into
a live slice editor (same building blocks, fewer simultaneous editors).

## Testing

- **PHP Block Builder:** IntelliJ platform test fixtures with PHP sample files
  (class + interface, traits, injected deps, intra-class calls, unsupported
  files) asserting the resulting Block Model.
- **Layout Engine:** plain unit tests; same model → same positions.
- **Slice editing:** tests that edits in a slice editor modify the correct range
  of the real document, and that adding a method yields a new block.
- **Canvas visuals:** manual verification in a sandbox IDE (`runIde`); no
  automated UI tests in v1.
