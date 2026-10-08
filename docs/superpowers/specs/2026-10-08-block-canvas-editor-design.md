# Block Canvas Editor — Design

**Date:** 2026-10-08
**Status:** Approved; revised 2026-10-08 (free graph layout replaces columns)

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
explanations, VS Code, terminal.

## User experience

- The block canvas **is** the editor for supported PHP files. It replaces the
  text editor; it is not a side panel or preview.
- A setting enables it as the default for all supported files ("set view to
  blocks"). A command / button opens a given file as blocks.
- A hidden toggle (shortcut / command) switches a file to the classic text view.
- Unsupported files open in the classic text editor automatically, with a small
  notice saying why.

### Canvas layout (free graph, left to right)

The graph reads **left to right**. There are no fixed three columns: the
relationships decide how many columns there are and what goes in them.

```
                                    [header: namespace + use]
 [abstract Base] ──extends──►  ┌──────────────┐ ──► [bar()] ──calls──► (footest)
 [interface X] ──implements──► │ class Child  │ ──► [footest()]
 [interface Y] ──implements──► │ props, ctor  │ ──► [baz()]
 [trait T] ──uses──────────►   └──────────────┘
      ▲
 [+] reveal Base's own parents → another column further left
```

- **Automatic layered layout, left to right.** Structural links decide the
  columns. Parents, interfaces, traits and dependencies sit to the **left** of
  the type they feed into, the class is in the **middle**, and its methods are
  to the **right**. Revealing a grandparent adds a column further left.
- **Lanes.** Each column is a *lane*: related types, the class (with the header
  above it), and the methods. Blocks fill a lane top to bottom, in source order
  (related types are ordered to reduce crossing lines). All lanes are
  **top-aligned**, so a lane only grows downwards: a newly added method appears
  at the bottom of the methods lane (or between methods, if it was typed between
  them in the file), and no other block moves. A lane taller than the maximum
  height wraps into an extra lane to its right (e.g. a class with 20 methods
  gets two method lanes).
- The header block sits directly above the class block, in the class's column.
- **Drag to arrange.** Any block can be dragged. A dragged block is *pinned*:
  it leaves its lane and stays where it was put, and the rest of the lane closes
  the gap. Auto-placed blocks move down out of the way of pinned ones, staying
  in their lane. New blocks are auto-placed by the same lane rules; they never
  float freely.
- **Pins are per user** and stored in the IDE workspace (`.idea/workspace.xml`,
  normally not in git), keyed by file and block id. If a block's id changes
  (e.g. a method is renamed), its pin is dropped and the block is auto-placed.
- **Deterministic:** the same model plus the same pins always gives the same
  picture.
- **Depth on demand:** one level of related types (direct parent, interfaces,
  traits, dependencies) is shown by default. Each related-type block has a `+`
  that reveals *its* parents, interfaces and traits, as deep as you want to go.
- Interface, trait and enum files use the same rules: that type is the central
  block (enum cases count as constants), with its methods to its right.

### Related-type lanes (staircase)

Parents, interfaces, traits and dependencies each get their **own lane**, in that order from top to bottom, each in its
own height band (a staircase). All lanes are right-aligned one gap left of the class, so they stay close to it and
lines into the class never cross other blocks. Revealed deeper levels extend a lane further left.

Inside the methods lane, a method is followed directly by the methods it calls (otherwise source order).

A **Tidy up** button on the canvas (and View → Reset Blocks Layout) drops all dragged positions of the file.

### Lines

- Lines are orthogonal (horizontal/vertical) elbows: out of the source's side, along a vertical, into the target.
  Lines into the same block each get their own vertical and entry point, so they never merge. A call between
  stacked methods loops out on the right and comes back in.
- Where a simple elbow would cross a block (e.g. after dragging), the line is routed around blocks instead.
- **Colour carries meaning, not dash patterns** (all lines are solid): extends blue, implements green, uses trait
  amber, injected purple, has method grey, calls red, implemented/used by teal. Override lines each get their own
  colour from a separate palette. A legend in the corner lists the kinds present.
- Kinds: `implements`, `extends`, `uses`, `injects` (into the type), `owns` (type → method), `calls`
  (method → method), `overrides` (method → the parent/interface method it implements or overrides; lands on that
  method's line when the target block is expanded), `implemented by` (type → implementer).
- `calls` and `overrides` are drawn only for the focused block. In a parent/interface block, an override line shows
  only while the caret is inside the overridden method.
- A method typed inside the focused block pops out into its own block when focus leaves that block.

### Implemented by (reverse lanes)

Opening an **interface**, **trait** or **abstract class** also shows who builds on it, mirrored to the right:

```
[interface Renderable] ─► [render();]
                     └──► [class Order]   ─► [Order::render()]
                     └──► [class Invoice] ─► [Invoice::render()]
```

- **Implementers lane** (right of the type's own methods): classes that directly implement the interface,
  directly extend the abstract class, or use the trait. Project code only (libraries/vendor are skipped).
  At most 10, alphabetical; if there are more, an extra block says "and N more".
- **Implementations lane** (right of the implementers): only the methods of each implementer that implement or
  override a method of the opened type. Collapsed by default, expandable and editable like other-file blocks.
- Lines: type → implementer (`implemented by`), implementer → its implementation (`has method`), and each
  implementation → the type's own method (`overrides`, focus-only, coloured per line).

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
Works across the whole canvas. Uses the IDE's own usage search, not text matching.
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
   - `Link`: kind (`implements | extends | uses | injects | owns | calls | overrides`),
     from, to, and an optional target range (for lines that land on one method
     inside a block).
   - No knowledge of PHP or rendering. Future languages produce the same model.

2. **PHP Block Builder** (one per language)
   - Input: PHP PSI file plus the set of revealed related-type blocks. Output:
     Block Model, or `Unsupported(reason)`.
   - Extracts header, class block, methods, interfaces/parent/traits (resolved
     via PSI to their files, plus the parents of every revealed block),
     constructor-injected dependencies, intra-class method calls, and
     overrides/implements links to parent methods.

3. **Layout Engine**
   - Input: Block Model, block sizes, pinned positions. Output: block positions.
   - Pure and deterministic; left-to-right layered graph rules above. Pinned blocks keep
     their positions, and auto-placed blocks never overlap them.
   - A separate pure geometry helper computes line endpoints between two
     rectangles (or a rectangle and a line inside it).

4. **Canvas Editor** (JetBrains UI)
   - A `FileEditorProvider` registered for PHP files that replaces the default
     text editor when enabled.
   - Swing canvas with pan/zoom; renders blocks, arrows, highlight borders.
   - Hosts one slice editor per expanded block. Hidden ranges inside a slice
     cover whole lines, so the class block has no editable holes where its
     methods were (found in the spike as "ghost typing").
   - Drag to pin blocks; pins persisted per user in the workspace.
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
- **Very large classes (50+ methods):** v1 wraps the method column into extra
  columns;
  optimize later if needed.
- **Stale pins:** pins for block ids that no longer exist are ignored and
  dropped on the next save.

## Technology

- Kotlin, IntelliJ Platform Gradle Plugin.
- Target PhpStorm 2025.x+, depending on the PHP plugin.
- Swing UI that follows the IDE theme (light/dark).
- Fully local; no network, no AI.

## Key risk and first step

**Risk:** a real IntelliJ editor that shows and edits only a range of a
document, with completion and inspections still working, is not a standard
platform feature.

**Result:** the spike passed (see
`docs/superpowers/research/2026-10-08-slice-editor-findings.md`). Approach A
(fold-based slice editors) is confirmed.

**First step (throwaway spike):** prove that a slice editor can display one
method of a PHP file, keep PHP completion working, and write edits back to the
right lines of the real file. If it works, proceed with this design. If not,
fall back to read-only highlighted blocks where double-click turns one block into
a live slice editor (same building blocks, fewer simultaneous editors).

## Testing

- **PHP Block Builder:** IntelliJ platform test fixtures with PHP sample files
  (class + interface, traits, injected deps, intra-class calls, unsupported
  files) asserting the resulting Block Model.
- **Layout Engine:** plain unit tests: layering, wrapping, pins respected,
  no overlaps, same input → same positions.
- **Slice editing:** tests that edits in a slice editor modify the correct range
  of the real document, and that adding a method yields a new block.
- **Canvas visuals:** manual verification in a sandbox IDE (`runIde`); no
  automated UI tests in v1.
