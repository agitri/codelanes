# Canvas: See and Edit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open a supported PHP file as a pannable, zoomable canvas of real, editable code blocks laid out left to right (related types → class → methods). Blocks can be dragged (positions remembered per user), collapsed and expanded, and the canvas keeps its last good state while the code is broken.

**Architecture:** Pure, unit-tested logic decides *what* to show and *where*: a left-to-right layered `LayoutEngine` with pins, `ArrowGeometry`, `SliceRanges` and `CaretPolicy` (which kill "ghost typing"), and `RebuildPolicy` (last-good model). Thin platform layers decide *how*: `SliceEditor` (a real IntelliJ editor folded down to one block), `BlocksCanvas` and `BlockView` (Swing), `BlocksSession` (rebuilds the model on document changes and reconciles views), and `BlocksEditorProvider` / `BlocksFileEditor` (plugs the canvas in as PhpStorm's editor for PHP files).

**Tech Stack:** Kotlin 2.4, IntelliJ Platform Gradle Plugin 2.19, PhpStorm 2026.1 + PHP plugin, Swing, JUnit 4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-10-08-block-canvas-editor-design.md` (sections: Canvas layout, Arrows, Blocks from other files, Editing, Error handling). Spike findings: `docs/superpowers/research/2026-10-08-slice-editor-findings.md`.

## Global Constraints

- The layout reads left to right in top-aligned lanes: related types left, class middle, methods right. There are no fixed columns; structural links decide the lanes. New blocks join the bottom of their lane (in source order) and never move other lanes.
- The file on disk is the single source of truth; the canvas never stores code.
- Cross-file references only via PSI; unresolved ⇒ no block.
- Pins (dragged positions) are per user, stored in `StoragePathMacros.WORKSPACE_FILE`, keyed by file path + block id; stale pins are dropped.
- Fully local: no network, no AI.
- Every Gradle command needs `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home` (no system JDK on this machine).
- Base package `com.readcodelikeahuman`.

## Out of scope (plan 2, "Canvas: interactions")

Usage highlighting, `+` reveal of deeper parents, overrides/implements links, `+ method` button and delete, keyboard block navigation, keeping Cmd+click on the canvas.

## Review Focus

1. **Ghost typing:** no caret position reachable in the class block may insert text onto a line that belongs to a method. → Task 4 `testNoReachableCaretPositionLandsOnAMethodLine`.
2. **Broken code while typing:** blocks must not vanish or jump while the PSI has errors. → Task 6 tests, Task 9 `testSyntaxErrorKeepsLastGoodModel`.
3. **Renaming a dragged (pinned) method:** stale pin is dropped, no crash, no ghost view. → Task 9 `testRenamedBlockDropsItsPin`.
4. **File turns unsupported while open** (e.g. `echo` added before the class): keep the last good blocks with a notice, no exception. → Task 9 `testUnsupportedEditKeepsLastGoodModelWithNotice`.
5. **Pins and overlaps:** auto-placed blocks never overlap pinned ones or each other. → Task 2 `pinnedBlockKeepsPositionAndOthersMoveAway`, `noTwoBlocksOverlap`.
6. **Stable lanes:** adding a method puts it at the bottom of the methods lane and moves nothing else. → Task 2 `newMethodAppendsBelowOthersAndNothingElseMoves`, `pinnedBlockLeavesNoGapInItsLane`.

## File Structure

```
src/main/kotlin/com/readcodelikeahuman/
  model/BlockModel.kt                  + Block.summary
  php/PhpBlockBuilder.kt               fills summary for other-file blocks
  layout/LayoutEngine.kt               REWRITE: left-to-right layered layout with pins
  layout/ArrowGeometry.kt              line endpoints between two rects
  editor/SliceRanges.kt                fold ranges incl. whole-line holes
  editor/CaretPolicy.kt                where the caret may sit in a slice
  editor/SliceEditor.kt                real editor folded down to one block
  canvas/RebuildPolicy.kt              last-good model decision
  canvas/BlockView.kt                  one block: title bar + body
  canvas/BlocksCanvas.kt               pan/zoom panel, paints arrows
  canvas/BlocksSession.kt              model ↔ views ↔ slice editors
  settings/PinStore.kt                 per-user dragged positions
  settings/BlocksSettings.kt           "open PHP as blocks by default"
  ide/BlocksFileEditor.kt              FileEditor wrapping a session
  ide/BlocksEditorProvider.kt          registers the canvas for PHP files
  ide/BlocksUnavailableNotification.kt "why not blocks" banner on text editor
  ide/ToggleBlocksViewAction.kt        hidden toggle blocks ↔ text
  ide/BlocksByDefaultAction.kt         setting toggle
src/main/resources/META-INF/plugin.xml registrations
src/test/kotlin/com/readcodelikeahuman/ matching *Test.kt per unit
```

---

### Task 1: Block summaries for other-file blocks

Collapsed other-file blocks show their name plus method signatures (spec: "collapsed (name + signatures)").

**Files:**
- Modify: `src/main/kotlin/com/readcodelikeahuman/model/BlockModel.kt`
- Modify: `src/main/kotlin/com/readcodelikeahuman/php/PhpBlockBuilder.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderSummaryTest.kt`

**Interfaces:**
- Produces: `Block.summary: List<String>` (default `emptyList()`, placed after `collapsed`). For INTERFACE/PARENT/TRAIT/DEPENDENCY blocks, it contains the target's own method titles in source order (same format as method block titles, e.g. `bar()`, `footest(string $s)`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind

class PhpBlockBuilderSummaryTest : PhpBuilderTestCase() {
    fun testOtherFileBlocksListTheirMethodSignatures() {
        addPhp(
            "src/Barro.php",
            "<?php\nnamespace App;\ninterface Barro\n{\n    public function bar(): void;\n    public function footest(string \$s): string;\n}\n",
        )
        val model = supported("<?php\nnamespace App;\nclass Foo implements Barro\n{\n    public function bar(): void {}\n    public function footest(string \$s): string { return \$s; }\n}\n")
        assertEquals(listOf("bar()", "footest(string \$s)"), model.block("interface:\\App\\Barro").summary)
    }

    fun testOwnBlocksHaveNoSummary() {
        val model = supported("<?php\nclass Foo\n{\n    public function bar(): void {}\n}\n")
        assertTrue(model.blocks.filter { it.kind != BlockKind.INTERFACE }.all { it.summary.isEmpty() })
    }
}
```

Save as `src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderSummaryTest.kt`.

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.PhpBlockBuilderSummaryTest"`
Expected: FAIL — compile error, `summary` unresolved.

- [ ] **Step 3: Implement**

In `BlockModel.kt`, add the field to `Block` after `collapsed`:
```kotlin
    val collapsed: Boolean = false,
    /** One line per member signature; shown in the body of a collapsed block. */
    val summary: List<String> = emptyList(),
```

In `PhpBlockBuilder.externalBlock`, add the summary:
```kotlin
    private fun externalBlock(kind: BlockKind, target: PhpClass): Block = Block(
        id = "${kind.name.lowercase()}:${target.fqn}",
        kind = kind,
        title = "${keyword(target)} ${target.name}",
        filePath = target.containingFile.virtualFile.path,
        range = rangeWithDoc(target),
        collapsed = true,
        summary = target.ownMethods.sortedBy { it.textRange.startOffset }.map(::methodTitle),
    )
```

- [ ] **Step 4: Run the PHP tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.*"`
Expected: PASS (all builder tests, including the 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: add member summaries to other-file blocks"
```

---

### Task 2: Left-to-right graph layout with pins

Replaces the three-column engine. Lanes (columns) come from structural links (longest-path layering, then compacted so sources sit right next to what they feed). Inside a lane, blocks stack top to bottom in model (source) order, reordered by neighbours (barycenter sweeps) only where that reduces crossings; blocks that share the same neighbours, like the methods of one class, keep source order. All lanes are **top-aligned** at y = 0, so adding a block to a lane never moves blocks in other lanes. A lane that gets too tall wraps into an extra lane. Pinned blocks are taken **out** of the lane flow (the lane closes the gap) and keep their positions; the rest are pushed down until nothing overlaps.

**Files:**
- Rewrite: `src/main/kotlin/com/readcodelikeahuman/layout/LayoutEngine.kt`
- Rewrite: `src/test/kotlin/com/readcodelikeahuman/layout/LayoutEngineTest.kt`

**Interfaces:**
- Consumes: `Block`, `BlockKind`, `BlockModel`, `Link`, `LinkKind` (Task 3 of the core plan).
- Produces:
  - `data class Size(val width: Int, val height: Int)`
  - `data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)` with `right`, `bottom`, `centerX`, `centerY`, `fun overlaps(other: Rect, gap: Int = 0): Boolean`
  - `data class Point(val x: Int, val y: Int)`
  - `data class Layout(val rects: Map<String, Rect>)` (the old `Arrow` type is removed; Task 3 replaces it)
  - `object LayoutEngine { H_GAP = 96; V_GAP = 24; PIN_GAP = 16; MAX_COLUMN_HEIGHT = 1400; fun layout(model: BlockModel, sizeOf: (Block) -> Size, pins: Map<String, Point> = emptyMap()): Layout }`
  - Structural link kinds: EXTENDS, IMPLEMENTS, USES, INJECTS, OWNS. The header goes directly above the class, in the class's lane. Lanes are top-aligned (y = 0). Pinned blocks don't take a slot in their lane.

- [ ] **Step 1: Write the failing tests** (replace the whole file)

```kotlin
package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockKind.CLASS
import com.readcodelikeahuman.model.BlockKind.HEADER
import com.readcodelikeahuman.model.BlockKind.INTERFACE
import com.readcodelikeahuman.model.BlockKind.METHOD
import com.readcodelikeahuman.model.BlockKind.PARENT
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind.CALLS
import com.readcodelikeahuman.model.LinkKind.EXTENDS
import com.readcodelikeahuman.model.LinkKind.IMPLEMENTS
import com.readcodelikeahuman.model.LinkKind.OWNS
import com.readcodelikeahuman.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {
    private fun block(id: String, kind: BlockKind) =
        Block(id = id, kind = kind, title = id, filePath = "/Foo.php", range = SourceRange(0, 1))

    private val sizeOf: (Block) -> Size = { if (it.kind == CLASS) Size(300, 160) else Size(200, 60) }

    private val foo = BlockModel(
        listOf(
            block("header", HEADER),
            block("class:Foo", CLASS),
            block("method:bar", METHOD),
            block("method:footest", METHOD),
            block("interface:X", INTERFACE),
            block("interface:Y", INTERFACE),
            block("parent:Base", PARENT),
        ),
        listOf(
            Link(OWNS, "class:Foo", "method:bar"),
            Link(OWNS, "class:Foo", "method:footest"),
            Link(IMPLEMENTS, "interface:X", "class:Foo"),
            Link(IMPLEMENTS, "interface:Y", "class:Foo"),
            Link(EXTENDS, "parent:Base", "class:Foo"),
            Link(CALLS, "method:bar", "method:footest"),
        ),
    )

    private fun assertNoOverlap(rects: Map<String, Rect>) {
        val entries = rects.entries.toList()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            assertFalse("${entries[i].key} overlaps ${entries[j].key}", entries[i].value.overlaps(entries[j].value))
        }
    }

    @Test
    fun relatedTypesLeftOfClassAndMethodsRight() {
        val r = LayoutEngine.layout(foo, sizeOf).rects
        val cls = r.getValue("class:Foo")
        listOf("interface:X", "interface:Y", "parent:Base").forEach { assertTrue(it, r.getValue(it).right <= cls.x) }
        listOf("method:bar", "method:footest").forEach { assertTrue(it, r.getValue(it).x >= cls.right) }
    }

    @Test
    fun headerSitsDirectlyAboveClass() {
        val r = LayoutEngine.layout(foo, sizeOf).rects
        val header = r.getValue("header")
        val cls = r.getValue("class:Foo")
        assertEquals(cls.x, header.x)
        assertTrue(header.bottom <= cls.y)
    }

    @Test
    fun noTwoBlocksOverlap() {
        assertNoOverlap(LayoutEngine.layout(foo, sizeOf).rects)
    }

    @Test
    fun tallMethodColumnWrapsToTheRight() {
        val methods = (1..30).map { block("method:m$it", METHOD) }
        val model = BlockModel(listOf(block("class:A", CLASS)) + methods, methods.map { Link(OWNS, "class:A", it.id) })
        val r = LayoutEngine.layout(model, sizeOf).rects
        val cls = r.getValue("class:A")
        val columns = methods.map { r.getValue(it.id) }.groupBy { it.x }
        assertTrue("expected wrapping, got ${columns.size} column(s)", columns.size >= 2)
        columns.values.forEach { col ->
            assertTrue(col.maxOf { it.bottom } - col.minOf { it.y } <= LayoutEngine.MAX_COLUMN_HEIGHT)
            col.forEach { assertTrue(it.x >= cls.right) }
        }
        assertNoOverlap(r)
    }

    @Test
    fun revealedChainsDoNotCross() {
        val model = BlockModel(
            listOf(
                block("interface:I0", INTERFACE),
                block("parent:G", PARENT),
                block("parent:P", PARENT),
                block("interface:I1", INTERFACE),
                block("class:A", CLASS),
            ),
            listOf(
                Link(EXTENDS, "parent:G", "parent:P"),
                Link(EXTENDS, "interface:I0", "interface:I1"),
                Link(EXTENDS, "parent:P", "class:A"),
                Link(IMPLEMENTS, "interface:I1", "class:A"),
            ),
        )
        val r = LayoutEngine.layout(model, sizeOf).rects
        assertTrue(r.getValue("parent:G").right <= r.getValue("parent:P").x)
        assertTrue(r.getValue("interface:I0").right <= r.getValue("interface:I1").x)
        assertTrue(r.getValue("parent:P").right <= r.getValue("class:A").x)
        assertEquals(
            r.getValue("parent:G").y < r.getValue("interface:I0").y,
            r.getValue("parent:P").y < r.getValue("interface:I1").y,
        )
    }

    @Test
    fun pinnedBlockKeepsPositionAndOthersMoveAway() {
        val classPos = LayoutEngine.layout(foo, sizeOf).rects.getValue("class:Foo")
        val pins = mapOf("method:bar" to Point(classPos.x, classPos.y))
        val r = LayoutEngine.layout(foo, sizeOf, pins).rects
        assertEquals(classPos.x, r.getValue("method:bar").x)
        assertEquals(classPos.y, r.getValue("method:bar").y)
        assertNoOverlap(r)
    }

    @Test
    fun newMethodAppendsBelowOthersAndNothingElseMoves() {
        val before = LayoutEngine.layout(foo, sizeOf).rects
        val grown = BlockModel(
            foo.blocks + block("method:added", METHOD),
            foo.links + Link(OWNS, "class:Foo", "method:added"),
        )
        val after = LayoutEngine.layout(grown, sizeOf).rects
        before.forEach { (id, rect) -> assertEquals(id, rect, after.getValue(id)) }
        val added = after.getValue("method:added")
        val footest = after.getValue("method:footest")
        assertEquals(footest.x, added.x)
        assertEquals(footest.bottom + LayoutEngine.V_GAP, added.y)
    }

    @Test
    fun pinnedBlockLeavesNoGapInItsLane() {
        val auto = LayoutEngine.layout(foo, sizeOf).rects
        val r = LayoutEngine.layout(foo, sizeOf, mapOf("method:bar" to Point(2000, 2000))).rects
        assertEquals(auto.getValue("method:bar").y, r.getValue("method:footest").y)
        assertEquals(auto.getValue("method:bar").x, r.getValue("method:footest").x)
    }

    @Test
    fun stalePinsAreIgnored() {
        assertEquals(
            LayoutEngine.layout(foo, sizeOf),
            LayoutEngine.layout(foo, sizeOf, mapOf("method:gone" to Point(5, 5))),
        )
    }

    @Test
    fun isDeterministic() {
        assertEquals(LayoutEngine.layout(foo, sizeOf), LayoutEngine.layout(foo, sizeOf))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.layout.LayoutEngineTest"`
Expected: FAIL — compile errors (`bottom`, `overlaps`, `MAX_COLUMN_HEIGHT`, 3-arg `layout` unresolved).

- [ ] **Step 3: Implement** (replace the whole file)

```kotlin
package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.LinkKind

data class Size(val width: Int, val height: Int)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    /** True if the rects intersect or come closer than [gap]. */
    fun overlaps(other: Rect, gap: Int = 0): Boolean =
        x < other.right + gap && other.x < right + gap && y < other.bottom + gap && other.y < bottom + gap
}

data class Point(val x: Int, val y: Int)

data class Layout(val rects: Map<String, Rect>)

/**
 * Left-to-right layered layout: what a type builds on sits left of it, what it owns sits right of it.
 * Pinned blocks keep their positions; everything else avoids them.
 */
object LayoutEngine {
    const val H_GAP = 96
    const val V_GAP = 24
    const val PIN_GAP = 16
    const val MAX_COLUMN_HEIGHT = 1400

    private val STRUCTURAL = setOf(LinkKind.EXTENDS, LinkKind.IMPLEMENTS, LinkKind.USES, LinkKind.INJECTS, LinkKind.OWNS)

    fun layout(model: BlockModel, sizeOf: (Block) -> Size, pins: Map<String, Point> = emptyMap()): Layout {
        val rank = ranks(model)
        val columns = orderedColumns(model, rank)
        val auto = place(columns.map { lane -> lane.filter { it.id !in pins } }, sizeOf)
        val pinned = model.blocks.filter { it.id in pins }.associate { block ->
            val size = sizeOf(block)
            val at = pins.getValue(block.id)
            block.id to Rect(at.x, at.y, size.width, size.height)
        }
        return Layout(applyPins(model, auto, pinned))
    }

    /** Longest-path ranks over structural links, compacted so sources sit next to what they feed. */
    private fun ranks(model: BlockModel): Map<String, Int> {
        val edges = model.links.filter { it.kind in STRUCTURAL }
        val rank = model.blocks.associate { it.id to 0 }.toMutableMap()
        for (pass in model.blocks.indices) {
            var changed = false
            for (edge in edges) {
                val wanted = rank.getValue(edge.from) + 1
                if (rank.getValue(edge.to) < wanted) {
                    rank[edge.to] = wanted
                    changed = true
                }
            }
            if (!changed) break
        }
        for (pass in model.blocks.indices) {
            var changed = false
            for (block in model.blocks) {
                val next = edges.filter { it.from == block.id }.minOfOrNull { rank.getValue(it.to) } ?: continue
                if (next - 1 > rank.getValue(block.id)) {
                    rank[block.id] = next - 1
                    changed = true
                }
            }
            if (!changed) break
        }
        model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            model.ofKind(BlockKind.HEADER).forEach { rank[it.id] = rank.getValue(cls.id) }
        }
        return rank
    }

    /** Blocks per rank, ordered by neighbour barycenters (one sweep right, one sweep left); header before class. */
    private fun orderedColumns(model: BlockModel, rank: Map<String, Int>): List<List<Block>> {
        val maxRank = rank.values.maxOrNull() ?: return emptyList()
        val columns = (0..maxRank).map { r -> model.blocks.filter { rank.getValue(it.id) == r }.toMutableList() }
        val edges = model.links.filter { it.kind in STRUCTURAL }
        fun position(id: String): Int = columns[rank.getValue(id)].indexOfFirst { it.id == id }

        for (r in 1..maxRank) reorder(columns[r]) { b -> edges.filter { it.to == b.id }.map { position(it.from) } }
        for (r in maxRank - 1 downTo 0) reorder(columns[r]) { b -> edges.filter { it.from == b.id }.map { position(it.to) } }

        model.ofKind(BlockKind.CLASS).firstOrNull()?.let { cls ->
            val column = columns[rank.getValue(cls.id)]
            val headers = column.filter { it.kind == BlockKind.HEADER }
            column.removeAll(headers)
            column.addAll(column.indexOf(cls), headers)
        }
        return columns
    }

    private fun reorder(column: MutableList<Block>, neighbours: (Block) -> List<Int>) {
        val sorted = column.withIndex()
            .sortedWith(compareBy({ neighbours(it.value).takeIf { n -> n.isNotEmpty() }?.average() ?: it.index.toDouble() }, { it.index }))
            .map { it.value }
        column.clear()
        column.addAll(sorted)
    }

    /** Top-aligned lanes, left to right; a lane only grows downwards. */
    private fun place(columns: List<List<Block>>, sizeOf: (Block) -> Size): Map<String, Rect> {
        val stacks = columns.flatMap { wrap(it, sizeOf) }.filter { it.isNotEmpty() }
        val rects = linkedMapOf<String, Rect>()
        var x = 0
        for (stack in stacks) {
            var y = 0
            var width = 0
            for (block in stack) {
                val size = sizeOf(block)
                rects[block.id] = Rect(x, y, size.width, size.height)
                y += size.height + V_GAP
                width = maxOf(width, size.width)
            }
            x += width + H_GAP
        }
        return rects
    }

    private fun wrap(column: List<Block>, sizeOf: (Block) -> Size): List<List<Block>> {
        val stacks = mutableListOf(mutableListOf<Block>())
        var height = 0
        for (block in column) {
            val h = sizeOf(block).height
            if (stacks.last().isNotEmpty() && height + V_GAP + h > MAX_COLUMN_HEIGHT) {
                stacks += mutableListOf<Block>()
                height = 0
            }
            height += (if (stacks.last().isEmpty()) 0 else V_GAP) + h
            stacks.last() += block
        }
        return stacks
    }

    /** Pinned rects stay put; auto-placed rects are pushed down (staying in their lane) until nothing overlaps. */
    private fun applyPins(model: BlockModel, auto: Map<String, Rect>, pinned: Map<String, Rect>): Map<String, Rect> {
        val result = pinned.toMutableMap()
        val placed = pinned.values.toMutableList()
        auto.entries
            .sortedWith(compareBy({ it.value.x }, { it.value.y }))
            .forEach { (id, start) ->
                var rect = start
                while (true) {
                    val hit = placed.firstOrNull { it.overlaps(rect, PIN_GAP) } ?: break
                    rect = rect.copy(y = hit.bottom + PIN_GAP)
                }
                result[id] = rect
                placed += rect
            }
        return model.blocks.associate { it.id to result.getValue(it.id) }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.layout.LayoutEngineTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: replace column layout with left-to-right graph layout and pins"
```

---

### Task 3: Arrow geometry

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/layout/ArrowGeometry.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/layout/ArrowGeometryTest.kt`

**Interfaces:**
- Consumes: `Rect`, `Point` (Task 2).
- Produces: `object ArrowGeometry { fun connect(from: Rect, to: Rect): Pair<Point, Point> }`. The target is to the right → from right-middle to left-middle. The target is to the left → from left-middle to right-middle. The rects share horizontal space (same column, e.g. a call between stacked methods) → right-middle to right-middle, so the canvas draws a side loop.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.readcodelikeahuman.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowGeometryTest {
    private val a = Rect(0, 0, 100, 40)

    @Test
    fun targetToTheRight() {
        assertEquals(Point(100, 20) to Point(200, 120), ArrowGeometry.connect(a, Rect(200, 100, 100, 40)))
    }

    @Test
    fun targetToTheLeft() {
        assertEquals(Point(300, 20) to Point(100, 120), ArrowGeometry.connect(Rect(300, 0, 100, 40), Rect(0, 100, 100, 40)))
    }

    @Test
    fun sameColumnLoopsOnTheRightSide() {
        assertEquals(Point(100, 20) to Point(100, 120), ArrowGeometry.connect(a, Rect(0, 100, 100, 40)))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.layout.ArrowGeometryTest"`
Expected: FAIL — `ArrowGeometry` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.readcodelikeahuman.layout

/** Where a line between two blocks starts and ends. */
object ArrowGeometry {
    fun connect(from: Rect, to: Rect): Pair<Point, Point> = when {
        to.x >= from.right -> Point(from.right, from.centerY) to Point(to.x, to.centerY)
        to.right <= from.x -> Point(from.x, from.centerY) to Point(to.right, to.centerY)
        else -> Point(from.right, from.centerY) to Point(to.right, to.centerY)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.layout.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: add arrow geometry between blocks"
```

---

### Task 4: Slice ranges and caret policy (the ghost-typing fix)

The spike found that folding only a method's text leaves its indentation and the blank lines around it visible and editable in the class block. Typing there lands on the method's line ("ghost typing"). The fix: hidden ranges cover **whole lines** (plus blank lines before them), and the caret may never sit at the start of such a hole. A hole starts at a line start, so text typed there would land in front of the method.

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/editor/SliceRanges.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/editor/CaretPolicy.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/editor/SliceRangesTest.kt`

**Interfaces:**
- Consumes: `SourceRange`.
- Produces:
  - `object SliceRanges { fun hidden(text: CharSequence, range: SourceRange, excluded: List<SourceRange>): List<SourceRange> }`. Sorted, merged ranges to fold away: everything before `range`, everything after it, and each excluded range widened to whole lines when only whitespace shares its first/last line.
  - `object CaretPolicy { fun adjust(text: CharSequence, bounds: SourceRange, folds: List<SourceRange>, offset: Int, previous: Int): Int }`. Clamps to bounds, moves out of folds in the direction of travel, and never rests at the start of a whole-line hole (going backwards it goes to the end of the previous line, otherwise to the hole's end).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.readcodelikeahuman.editor

import com.readcodelikeahuman.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceRangesTest {
    private val foo = """
        <?php
        class Foo
        {
            private string ${'$'}name = 'x';

            public function bar(): void
            {
                echo 1;
            }

            public function footest(): void
            {
            }
        }

    """.trimIndent()

    private val big = """
        <?php
        class Big
        {
            public function m1(): int { return 1; }
            public function m2(): int { return ${'$'}this->m1() + 1; }
            public function m3(): int { return ${'$'}this->m2() + 1; }
        }

    """.trimIndent()

    private fun classRange(text: String) = SourceRange(text.indexOf("class "), text.lastIndexOf('}') + 1)

    /** From "public function <name>" to the end of its closing brace (multi-line or one-line). */
    private fun methodRange(text: String, name: String): SourceRange {
        val start = text.indexOf("public function $name(")
        val lineEnd = text.indexOf('\n', start)
        val oneLiner = text.substring(start, lineEnd).trimEnd().endsWith("}")
        val end = if (oneLiner) text.lastIndexOf('}', lineEnd) + 1 else text.indexOf("\n    }", start) + 6
        return SourceRange(start, end)
    }

    private fun visible(text: String, folds: List<SourceRange>): String =
        text.indices.filter { o -> folds.none { it.contains(o) } }.map { text[it] }.joinToString("")

    @Test
    fun classSliceHidesMethodsAsWholeLines() {
        val excluded = listOf(methodRange(foo, "bar"), methodRange(foo, "footest"))
        val folds = SliceRanges.hidden(foo, classRange(foo), excluded)
        assertEquals("class Foo\n{\n    private string \$name = 'x';\n}", visible(foo, folds))
    }

    @Test
    fun methodSliceShowsOnlyTheMethod() {
        val bar = methodRange(foo, "bar")
        assertEquals(foo.substring(bar.start, bar.end), visible(foo, SliceRanges.hidden(foo, bar, emptyList())))
    }

    @Test
    fun oneLineMethodsLeaveNoHoles() {
        val excluded = listOf("m1", "m2", "m3").map { methodRange(big, it) }
        assertEquals("class Big\n{\n}", visible(big, SliceRanges.hidden(big, classRange(big), excluded)))
    }

    @Test
    fun noReachableCaretPositionLandsOnAMethodLine() {
        for ((text, names) in listOf(foo to listOf("bar", "footest"), big to listOf("m1", "m2", "m3"))) {
            val bounds = classRange(text)
            val excluded = names.map { methodRange(text, it) }
            val folds = SliceRanges.hidden(text, bounds, excluded)
            for (offset in bounds.start..bounds.end) {
                for (previous in listOf(offset - 1, offset + 1)) {
                    val caret = CaretPolicy.adjust(text, bounds, folds, offset, previous)
                    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
                    val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
                    assertTrue(
                        "caret $caret (from $offset) sits on a method line",
                        (lineStart until lineEnd).none { o -> excluded.any { it.contains(o) } },
                    )
                }
            }
        }
    }

    @Test
    fun caretIsClampedToBounds() {
        val bar = methodRange(foo, "bar")
        val folds = SliceRanges.hidden(foo, bar, emptyList())
        assertEquals(bar.start, CaretPolicy.adjust(foo, bar, folds, 0, 5))
        assertEquals(bar.end, CaretPolicy.adjust(foo, bar, folds, foo.length, 5))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.editor.SliceRangesTest"`
Expected: FAIL — `SliceRanges`, `CaretPolicy` unresolved.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/readcodelikeahuman/editor/SliceRanges.kt`:
```kotlin
package com.readcodelikeahuman.editor

import com.readcodelikeahuman.model.SourceRange

/** Computes which parts of a document a slice editor folds away. */
object SliceRanges {
    fun hidden(text: CharSequence, range: SourceRange, excluded: List<SourceRange>): List<SourceRange> {
        val inner = excluded.sortedBy { it.start }.map { wholeLines(text, range, it) }
        val all = listOf(SourceRange(0, range.start)) + inner + SourceRange(range.end, text.length)
        return merge(all.filter { it.start < it.end })
    }

    /** Widens [e] to whole lines (and the blank lines above it) when only whitespace shares those lines. */
    private fun wholeLines(text: CharSequence, bounds: SourceRange, e: SourceRange): SourceRange {
        var start = e.start
        val lineStart = lineStartOf(text, start)
        if (isBlank(text, lineStart, start)) {
            start = lineStart
            while (start > bounds.start) {
                val previousLineStart = lineStartOf(text, start - 1)
                if (previousLineStart < bounds.start || !isBlank(text, previousLineStart, start - 1)) break
                start = previousLineStart
            }
        }
        var end = e.end
        val lineEnd = lineEndOf(text, end)
        if (isBlank(text, end, lineEnd) && lineEnd < bounds.end) end = lineEnd + 1
        return SourceRange(maxOf(start, bounds.start), minOf(end, bounds.end))
    }

    private fun merge(ranges: List<SourceRange>): List<SourceRange> {
        val result = mutableListOf<SourceRange>()
        for (r in ranges.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && r.start <= last.end) result[result.size - 1] = SourceRange(last.start, maxOf(last.end, r.end))
            else result += r
        }
        return result
    }

    internal fun lineStartOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEndOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun isBlank(text: CharSequence, from: Int, to: Int): Boolean = (from until to).all { text[it].isWhitespace() }
}
```

`src/main/kotlin/com/readcodelikeahuman/editor/CaretPolicy.kt`:
```kotlin
package com.readcodelikeahuman.editor

import com.readcodelikeahuman.model.SourceRange

/** Where the caret of a slice editor is allowed to rest. */
object CaretPolicy {
    fun adjust(text: CharSequence, bounds: SourceRange, folds: List<SourceRange>, offset: Int, previous: Int): Int {
        var o = offset.coerceIn(bounds.start, bounds.end)
        val forward = o >= previous
        folds.firstOrNull { o > it.start && o < it.end }?.let { o = if (forward) it.end else it.start }
        val isHole = o > bounds.start && o < bounds.end && SliceRanges.lineStartOf(text, o) == o &&
            folds.any { it.start == o }
        if (!isHole) return o
        val hole = folds.first { it.start == o }
        return if (!forward && o - 1 >= bounds.start) o - 1 else hole.end
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.editor.SliceRangesTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: whole-line slice folds and caret policy against ghost typing"
```

---

### Task 5: SliceEditor

The production version of the spike technique. It is a real editor on the real document, folded with `SliceRanges` and with its caret steered by `CaretPolicy`. Bounds are tracked with a greedy-right `RangeMarker`, so typing at the end of a block grows it until the next rebuild.

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/editor/SliceEditor.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/editor/SliceEditorTest.kt`

**Interfaces:**
- Consumes: `SliceRanges`, `CaretPolicy` (Task 4), `PhpBlockBuilder` (tests only).
- Produces: `class SliceEditor(project: Project, file: VirtualFile, document: Document) : Disposable` with `val editor: EditorEx`, `val component: JComponent`, `fun show(range: SourceRange, excluded: List<SourceRange>)` (re-folds; callable repeatedly), `fun setFontSize(size: Int)`. `dispose()` releases the editor.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.readcodelikeahuman.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.php.PhpBlockBuilder

class SliceEditorTest : BasePlatformTestCase() {
    private val source = """
        <?php
        class Foo
        {
            private string ${'$'}name = 'x';

            public function bar(): void
            {
                echo 1;
            }

            public function footest(): void
            {
            }
        }

    """.trimIndent()

    private fun open(): Pair<SliceEditor, BlockModel> {
        myFixture.configureByText("Foo.php", source)
        val model = (PhpBlockBuilder.build(myFixture.file) as BuildResult.Supported).model
        val slice = SliceEditor(project, myFixture.file.virtualFile, myFixture.editor.document)
        Disposer.register(testRootDisposable, slice)
        return slice to model
    }

    private fun visible(slice: SliceEditor): String {
        val text = slice.editor.document.charsSequence
        val folds = slice.editor.foldingModel.allFoldRegions.filter { !it.isExpanded }
        return text.indices.filter { o -> folds.none { o >= it.startOffset && o < it.endOffset } }.map { text[it] }.joinToString("")
    }

    fun testClassSliceShowsNoMethodLines() {
        val (slice, model) = open()
        val cls = model.block("class:\\Foo")
        slice.show(cls.range, cls.excluded)
        assertEquals("class Foo\n{\n    private string \$name = 'x';\n}", visible(slice))
    }

    fun testCaretIsClampedToTheSlice() {
        val (slice, model) = open()
        val bar = model.block("method:bar")
        slice.show(bar.range, bar.excluded)
        slice.editor.caretModel.moveToOffset(0)
        assertEquals(bar.range.start, slice.editor.caretModel.offset)
    }

    fun testTypingAtTheEndOfTheSliceIsNotClampedBack() {
        val (slice, model) = open()
        val bar = model.block("method:bar")
        slice.show(bar.range, bar.excluded)
        WriteCommandAction.runWriteCommandAction(project) {
            slice.editor.caretModel.moveToOffset(bar.range.end)
            EditorModificationUtil.insertStringAtCaret(slice.editor, " // end")
        }
        assertEquals(bar.range.end + 7, slice.editor.caretModel.offset)
    }

    fun testCaretSkipsTheHoleWhereMethodsWere() {
        val (slice, model) = open()
        val cls = model.block("class:\\Foo")
        slice.show(cls.range, cls.excluded)
        val hole = slice.editor.foldingModel.allFoldRegions.single { it.startOffset > cls.range.start && it.endOffset < cls.range.end }
        slice.editor.caretModel.moveToOffset(hole.startOffset - 2)
        slice.editor.caretModel.moveToOffset(hole.startOffset)
        assertEquals(hole.endOffset, slice.editor.caretModel.offset)
        slice.editor.caretModel.moveToOffset(hole.startOffset)
        assertEquals(hole.startOffset - 1, slice.editor.caretModel.offset)
    }

    fun testShowAgainReplacesTheFolds() {
        val (slice, model) = open()
        val cls = model.block("class:\\Foo")
        slice.show(cls.range, cls.excluded)
        slice.show(cls.range, emptyList())
        assertTrue(visible(slice).contains("function bar"))
    }
}
```

Note on `testCaretSkipsTheHoleWhereMethodsWere`: the second `moveToOffset(hole.startOffset)` comes from `hole.endOffset` (where the first one ended), so it travels backwards and must land at the end of the previous line.

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.editor.SliceEditorTest"`
Expected: FAIL — `SliceEditor` unresolved.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.editor.*"`
Expected: PASS (SliceRangesTest + 5 SliceEditorTest tests).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: add slice editor with whole-line folds and caret policy"
```

---

### Task 6: Rebuild policy (keep the last good model)

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/canvas/RebuildPolicy.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/canvas/RebuildPolicyTest.kt`

**Interfaces:**
- Consumes: `BlockModel`, `BuildResult`.
- Produces: `data class CanvasUpdate(val model: BlockModel?, val notice: String?)` and `object RebuildPolicy { const val SYNTAX_NOTICE = "Fix the syntax errors to update the blocks"; fun next(previous: BlockModel?, result: BuildResult, hasSyntaxErrors: Boolean): CanvasUpdate }`.
  - Syntax errors with a previous model → keep the previous model, with `SYNTAX_NOTICE`.
  - Supported → the new model, no notice.
  - Unsupported with a previous model → keep the previous model, with `"Blocks paused: <reason>"`.
  - Unsupported with no previous model → no model, with `<reason>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.readcodelikeahuman.canvas

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RebuildPolicyTest {
    private fun model(id: String) =
        BlockModel(listOf(Block(id, BlockKind.CLASS, id, "/Foo.php", SourceRange(0, 1))), emptyList())

    private val old = model("class:Old")
    private val new = model("class:New")

    @Test
    fun syntaxErrorsKeepThePreviousModel() {
        val update = RebuildPolicy.next(old, BuildResult.Supported(new), hasSyntaxErrors = true)
        assertSame(old, update.model)
        assertEquals(RebuildPolicy.SYNTAX_NOTICE, update.notice)
    }

    @Test
    fun supportedResultReplacesTheModel() {
        assertEquals(CanvasUpdate(new, null), RebuildPolicy.next(old, BuildResult.Supported(new), hasSyntaxErrors = false))
    }

    @Test
    fun unsupportedResultPausesOnThePreviousModel() {
        val update = RebuildPolicy.next(old, BuildResult.Unsupported("File contains code before the class"), false)
        assertEquals(CanvasUpdate(old, "Blocks paused: File contains code before the class"), update)
    }

    @Test
    fun unsupportedWithoutPreviousShowsTheReason() {
        assertEquals(CanvasUpdate(null, "Not a PHP file"), RebuildPolicy.next(null, BuildResult.Unsupported("Not a PHP file"), false))
    }

    @Test
    fun firstBuildWithErrorsStillShowsWhatParsed() {
        assertEquals(CanvasUpdate(new, null), RebuildPolicy.next(null, BuildResult.Supported(new), hasSyntaxErrors = true))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.canvas.RebuildPolicyTest"`
Expected: FAIL — `RebuildPolicy` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.readcodelikeahuman.canvas

import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult

data class CanvasUpdate(val model: BlockModel?, val notice: String?)

/** Decides what the canvas shows after a rebuild: never drop good blocks because of a half-typed edit. */
object RebuildPolicy {
    const val SYNTAX_NOTICE = "Fix the syntax errors to update the blocks"

    fun next(previous: BlockModel?, result: BuildResult, hasSyntaxErrors: Boolean): CanvasUpdate = when {
        hasSyntaxErrors && previous != null -> CanvasUpdate(previous, SYNTAX_NOTICE)
        result is BuildResult.Supported -> CanvasUpdate(result.model, null)
        previous != null -> CanvasUpdate(previous, "Blocks paused: ${(result as BuildResult.Unsupported).reason}")
        else -> CanvasUpdate(null, (result as BuildResult.Unsupported).reason)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.canvas.RebuildPolicyTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: keep last good block model while code is broken"
```

---

### Task 7: Pin store and settings

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/settings/PinStore.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/settings/BlocksSettings.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/settings/PinStoreTest.kt`

**Interfaces:**
- Consumes: `Point` (Task 2).
- Produces:
  - `class PinStore` (project light service, workspace storage): `companion fun getInstance(project: Project): PinStore`; `fun pins(filePath: String): Map<String, Point>`; `fun pin(filePath: String, blockId: String, at: Point)`; `fun prune(filePath: String, liveIds: Set<String>)`.
  - `class BlocksSettings` (application light service): `companion val instance: BlocksSettings`; `state.openAsBlocksByDefault: Boolean` (default `true`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.readcodelikeahuman.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.layout.Point

class PinStoreTest : BasePlatformTestCase() {
    private val file = "/project/src/Foo.php"
    private val other = "/project/src/Foo.php.bak"

    override fun setUp() {
        super.setUp()
        PinStore.getInstance(project).prune(file, emptySet())
        PinStore.getInstance(project).prune(other, emptySet())
    }

    fun testPinsAreStoredPerFile() {
        val store = PinStore.getInstance(project)
        store.pin(file, "method:bar", Point(10, 20))
        store.pin(other, "method:bar", Point(1, 2))
        assertEquals(mapOf("method:bar" to Point(10, 20)), store.pins(file))
    }

    fun testPruneDropsPinsOfVanishedBlocks() {
        val store = PinStore.getInstance(project)
        store.pin(file, "method:bar", Point(10, 20))
        store.pin(file, "method:baz", Point(30, 40))
        store.prune(file, setOf("method:baz"))
        assertEquals(mapOf("method:baz" to Point(30, 40)), store.pins(file))
    }

    fun testBlocksByDefaultIsOnInitially() {
        assertTrue(BlocksSettings().state.openAsBlocksByDefault)
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.settings.PinStoreTest"`
Expected: FAIL — `PinStore`, `BlocksSettings` unresolved.

- [ ] **Step 3: Implement**

`PinStore.kt`:
```kotlin
package com.readcodelikeahuman.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.readcodelikeahuman.layout.Point

/** Per-user dragged block positions, stored in the workspace file (not shared via git). */
@Service(Service.Level.PROJECT)
@State(name = "ReadCodeBlockPins", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PinStore : SimplePersistentStateComponent<PinStore.PinState>(PinState()) {
    class PinState : BaseState() {
        /** "filePath|blockId" → "x,y" */
        var pins by map<String, String>()
    }

    fun pins(filePath: String): Map<String, Point> =
        state.pins.entries
            .filter { it.key.startsWith("$filePath|") }
            .associate { (key, value) ->
                val (x, y) = value.split(',').map(String::toInt)
                key.substringAfter('|') to Point(x, y)
            }

    fun pin(filePath: String, blockId: String, at: Point) {
        state.pins["$filePath|$blockId"] = "${at.x},${at.y}"
        state.intIncrementModificationCount()
    }

    fun prune(filePath: String, liveIds: Set<String>) {
        val removed = state.pins.keys.removeIf { it.startsWith("$filePath|") && it.substringAfter('|') !in liveIds }
        if (removed) state.intIncrementModificationCount()
    }

    companion object {
        fun getInstance(project: Project): PinStore = project.service()
    }
}
```

`BlocksSettings.kt`:
```kotlin
package com.readcodelikeahuman.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "ReadCodeBlocksSettings", storages = [Storage("readCodeBlocks.xml")])
class BlocksSettings : SimplePersistentStateComponent<BlocksSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var openAsBlocksByDefault by property(true)
    }

    companion object {
        val instance: BlocksSettings get() = service()
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.settings.*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: add per-user pin store and blocks-by-default setting"
```

---

### Task 8: Canvas and block views

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/canvas/BlockView.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/canvas/BlocksCanvas.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/canvas/BlocksCanvasTest.kt`

**Interfaces:**
- Consumes: `Block`, `Link`, `LinkKind`, `Rect`, `Size`, `Point`, `ArrowGeometry`.
- Produces:
  - `class BlocksCanvas(listener: BlocksCanvas.Listener) : JPanel`
    - `interface Listener { fun blockMoved(id: String, position: Point); fun collapseToggled(id: String); fun zoomChanged() }`
    - `val listener`, `val zoom: Double` (0.5..2.0), `fun setZoom(value: Double)`
    - `var focusedId: String?`, `var notice: String?`
    - `fun setContent(views: Map<String, BlockView>, links: List<Link>)`, `fun place(rects: Map<String, Rect>)`
    - `fun toCanvas(screen: java.awt.Point): Point`, `internal fun visibleLinks(): List<Link>`
    - Initial pan offset is (40, 40). The screen rect is `pan + rect * zoom`.
  - `class BlockView(id: String, canvas: BlocksCanvas) : JPanel`
    - `fun update(block: Block, collapsed: Boolean, body: JComponent, zoom: Double)`, `fun naturalSize(zoom: Double): Size` (unscaled, capped at 900×600, minimum width 160)
    - Title bar: click toggles collapse, drag moves the block and reports `blockMoved` on release.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.readcodelikeahuman.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.layout.Point
import com.readcodelikeahuman.layout.Rect
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import java.awt.Rectangle

class BlocksCanvasTest : BasePlatformTestCase() {
    private object NoListener : BlocksCanvas.Listener {
        override fun blockMoved(id: String, position: Point) {}
        override fun collapseToggled(id: String) {}
        override fun zoomChanged() {}
    }

    fun testPlaceAppliesPanAndZoom() {
        val canvas = BlocksCanvas(NoListener)
        val view = BlockView("a", canvas)
        canvas.setContent(mapOf("a" to view), emptyList())
        canvas.place(mapOf("a" to Rect(10, 20, 100, 50)))
        assertEquals(Rectangle(50, 60, 100, 50), view.bounds)
        canvas.setZoom(2.0)
        canvas.place(mapOf("a" to Rect(10, 20, 100, 50)))
        assertEquals(Rectangle(60, 80, 200, 100), view.bounds)
        assertEquals(Point(10, 20), canvas.toCanvas(java.awt.Point(60, 80)))
    }

    fun testZoomIsClamped() {
        val canvas = BlocksCanvas(NoListener)
        canvas.setZoom(10.0)
        assertEquals(2.0, canvas.zoom)
        canvas.setZoom(0.1)
        assertEquals(0.5, canvas.zoom)
    }

    fun testCallsAreOnlyShownForTheFocusedBlock() {
        val canvas = BlocksCanvas(NoListener)
        val owns = Link(LinkKind.OWNS, "c", "a")
        val calls = Link(LinkKind.CALLS, "a", "b")
        canvas.setContent(listOf("a", "b", "c").associateWith { BlockView(it, canvas) }, listOf(owns, calls))
        assertEquals(listOf(owns), canvas.visibleLinks())
        canvas.focusedId = "b"
        assertEquals(listOf(owns, calls), canvas.visibleLinks())
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.canvas.BlocksCanvasTest"`
Expected: FAIL — `BlocksCanvas`, `BlockView` unresolved.

- [ ] **Step 3: Implement**

`BlockView.kt`:
```kotlin
package com.readcodelikeahuman.canvas

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.readcodelikeahuman.layout.Size
import com.readcodelikeahuman.model.Block
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.roundToInt

/** One block on the canvas: a title bar (click = collapse/expand, drag = move) above its body. */
class BlockView(val id: String, private val canvas: BlocksCanvas) : JPanel(BorderLayout()) {
    private val title = JBLabel()
    private val header = JPanel(BorderLayout())
    private var body: JComponent? = null

    init {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        header.border = JBUI.Borders.empty(4, 8)
        header.background = JBColor.namedColor("EditorTabs.background", JBColor.PanelBackground)
        header.add(title, BorderLayout.CENTER)
        add(header, BorderLayout.NORTH)

        val mover = object : MouseAdapter() {
            private var start: java.awt.Point? = null
            private var origin: java.awt.Point? = null
            private var dragged = false

            override fun mousePressed(e: MouseEvent) {
                start = e.locationOnScreen
                origin = location
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val s = start ?: return
                val o = origin ?: return
                setLocation(o.x + e.locationOnScreen.x - s.x, o.y + e.locationOnScreen.y - s.y)
                dragged = true
                canvas.repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragged) canvas.listener.blockMoved(id, canvas.toCanvas(location))
                start = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!dragged) canvas.listener.collapseToggled(id)
            }
        }
        header.addMouseListener(mover)
        header.addMouseMotionListener(mover)
    }

    fun update(block: Block, collapsed: Boolean, body: JComponent, zoom: Double) {
        title.text = (if (collapsed) "▸ " else "▾ ") + block.title
        title.font = JBFont.label().asBold().deriveFont((JBFont.label().size2D * zoom).toFloat())
        if (this.body !== body) {
            this.body?.let(::remove)
            add(body, BorderLayout.CENTER)
            this.body = body
        }
        revalidate()
    }

    /** Size at zoom 1, capped so huge blocks scroll inside instead of taking over the canvas. */
    fun naturalSize(zoom: Double): Size {
        val preferred = preferredSize
        return Size(
            (preferred.width / zoom).roundToInt().coerceIn(MIN_WIDTH, MAX_WIDTH),
            (preferred.height / zoom).roundToInt().coerceAtMost(MAX_HEIGHT),
        )
    }

    companion object {
        const val MIN_WIDTH = 160
        const val MAX_WIDTH = 900
        const val MAX_HEIGHT = 600
    }
}
```

`BlocksCanvas.kt`:
```kotlin
package com.readcodelikeahuman.canvas

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.readcodelikeahuman.layout.ArrowGeometry
import com.readcodelikeahuman.layout.Point
import com.readcodelikeahuman.layout.Rect
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Pannable, zoomable surface that hosts [BlockView]s and paints the lines between them. */
class BlocksCanvas(val listener: Listener) : JPanel(null) {
    interface Listener {
        fun blockMoved(id: String, position: Point)
        fun collapseToggled(id: String)
        fun zoomChanged()
    }

    var zoom: Double = 1.0
        private set
    var focusedId: String? = null
        set(value) { field = value; repaint() }
    var notice: String? = null
        set(value) { field = value; repaint() }

    private val pan = java.awt.Point(40, 40)
    private val views = linkedMapOf<String, BlockView>()
    private var rects: Map<String, Rect> = emptyMap()
    private var links: List<Link> = emptyList()

    init {
        isOpaque = true
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        val panner = object : MouseAdapter() {
            private var last: java.awt.Point? = null

            override fun mousePressed(e: MouseEvent) { last = e.point }
            override fun mouseReleased(e: MouseEvent) { last = null }

            override fun mouseDragged(e: MouseEvent) {
                val l = last ?: return
                pan.translate(e.x - l.x, e.y - l.y)
                last = e.point
                placeViews()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                when {
                    e.isMetaDown || e.isControlDown -> setZoom(zoom * 1.1.pow(-e.preciseWheelRotation))
                    e.isShiftDown -> { pan.translate((-e.preciseWheelRotation * 40).roundToInt(), 0); placeViews() }
                    else -> { pan.translate(0, (-e.preciseWheelRotation * 40).roundToInt()); placeViews() }
                }
            }
        }
        addMouseListener(panner)
        addMouseMotionListener(panner)
        addMouseWheelListener(panner)
    }

    fun setZoom(value: Double) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        listener.zoomChanged()
    }

    fun setContent(next: Map<String, BlockView>, links: List<Link>) {
        (views.keys - next.keys).forEach { remove(views.getValue(it)) }
        next.values.filter { it.parent !== this }.forEach { add(it) }
        views.clear()
        views.putAll(next)
        this.links = links
    }

    fun place(rects: Map<String, Rect>) {
        this.rects = rects
        placeViews()
    }

    fun toCanvas(screen: java.awt.Point): Point =
        Point(((screen.x - pan.x) / zoom).roundToInt(), ((screen.y - pan.y) / zoom).roundToInt())

    internal fun visibleLinks(): List<Link> =
        links.filter { it.kind != LinkKind.CALLS || focusedId == it.from || focusedId == it.to }

    private fun placeViews() {
        for ((id, view) in views) {
            val r = rects[id] ?: continue
            view.bounds = Rectangle(
                pan.x + (r.x * zoom).roundToInt(),
                pan.y + (r.y * zoom).roundToInt(),
                (r.width * zoom).roundToInt(),
                (r.height * zoom).roundToInt(),
            )
        }
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (link in visibleLinks()) {
                val a = views[link.from]?.bounds ?: continue
                val b = views[link.to]?.bounds ?: continue
                val (from, to) = ArrowGeometry.connect(a.toRect(), b.toRect())
                g2.color = colorFor(link.kind)
                g2.stroke = strokeFor(link.kind)
                if (from.x == to.x) {
                    val bulge = (40 * zoom).roundToInt()
                    g2.drawPolyline(intArrayOf(from.x, from.x + bulge, to.x + bulge, to.x), intArrayOf(from.y, from.y, to.y, to.y), 4)
                } else {
                    g2.drawLine(from.x, from.y, to.x, to.y)
                }
                arrowHead(g2, if (from.x == to.x) Point(to.x + 1, to.y) else from, to)
            }
            notice?.let {
                g2.font = JBFont.label().asBold()
                g2.color = JBColor.RED
                g2.drawString(it, 12, 20)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun arrowHead(g2: Graphics2D, from: Point, to: Point) {
        val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
        val size = 8 * zoom
        val xs = intArrayOf(to.x, (to.x - size * cos(angle - PI / 7)).roundToInt(), (to.x - size * cos(angle + PI / 7)).roundToInt())
        val ys = intArrayOf(to.y, (to.y - size * sin(angle - PI / 7)).roundToInt(), (to.y - size * sin(angle + PI / 7)).roundToInt())
        g2.fillPolygon(xs, ys, 3)
    }

    private fun colorFor(kind: LinkKind) = when (kind) {
        LinkKind.OWNS -> JBColor.GRAY
        LinkKind.CALLS -> JBColor.BLUE
        else -> JBColor.foreground()
    }

    private fun strokeFor(kind: LinkKind): BasicStroke {
        val width = (1.5 * zoom).toFloat()
        val dash = when (kind) {
            LinkKind.IMPLEMENTS -> floatArrayOf(8f, 6f)
            LinkKind.USES -> floatArrayOf(2f, 4f)
            LinkKind.INJECTS -> floatArrayOf(10f, 4f, 2f, 4f)
            else -> null
        }
        return BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
    }

    private fun Rectangle.toRect() = Rect(x, y, width, height)

    companion object {
        const val MIN_ZOOM = 0.5
        const val MAX_ZOOM = 2.0
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.canvas.*"`
Expected: PASS (RebuildPolicyTest + 3 BlocksCanvasTest tests).

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: add pannable zoomable canvas with draggable block views"
```

---

### Task 9: Blocks session (model ↔ views ↔ slice editors)

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/canvas/BlocksSession.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/canvas/BlocksSessionTest.kt`

**Interfaces:**
- Consumes: `PhpBlockBuilder`, `RebuildPolicy`, `LayoutEngine`, `SliceEditor`, `PinStore`, `BlocksCanvas`, `BlockView`.
- Produces: `class BlocksSession(project: Project, file: VirtualFile) : Disposable, BlocksCanvas.Listener`
  - `val canvas: BlocksCanvas`, `val model: BlockModel?`
  - `fun rebuildNow()`; document changes schedule a rebuild after `REBUILD_DELAY_MS = 300`
  - test accessors: `fun blockIds(): List<String>`, `fun hasSliceEditor(id: String): Boolean`, `fun viewBounds(id: String): java.awt.Rectangle?`
  - `Listener` implementation: `blockMoved` pins and relayouts, `collapseToggled` flips state and re-applies, `zoomChanged` re-applies with the new font size.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.readcodelikeahuman.canvas

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.layout.Point
import com.readcodelikeahuman.settings.PinStore

class BlocksSessionTest : BasePlatformTestCase() {
    private val source = """
        <?php
        namespace App;

        class Foo
        {
            private string ${'$'}name = 'x';

            public function bar(): string
            {
                return ${'$'}this->footest(${'$'}this->name);
            }

            public function footest(string ${'$'}s): string
            {
                return ${'$'}s;
            }
        }

    """.trimIndent()

    private fun open(): BlocksSession {
        val psi = myFixture.configureByText("Foo.php", source)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        return BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
    }

    /** Replaces the first occurrence of [old] like a user edit would (keeps markers outside the edit valid). */
    private fun edit(old: String, new: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val at = document.text.indexOf(old)
            assertTrue("'$old' not found", at >= 0)
            document.replaceString(at, at + old.length, new)
        }
    }

    fun testOpensWithOneViewPerBlockAndEditorsForExpandedBlocks() {
        val session = open()
        assertEquals(listOf("header", "class:\\App\\Foo", "method:bar", "method:footest"), session.blockIds())
        assertTrue(session.hasSliceEditor("class:\\App\\Foo"))
        assertTrue(session.hasSliceEditor("method:bar"))
        assertFalse(session.hasSliceEditor("header"))
    }

    fun testNewMethodAppearsAfterRebuild() {
        val session = open()
        edit("        return ${'$'}s;\n    }\n", "        return ${'$'}s;\n    }\n\n    public function added(): void {}\n")
        session.rebuildNow()
        assertTrue(session.blockIds().contains("method:added"))
        assertTrue(session.hasSliceEditor("method:added"))
    }

    fun testSyntaxErrorKeepsLastGoodModel() {
        val session = open()
        val before = session.model
        edit("    public function footest", "    public function (\n    public function footest")
        session.rebuildNow()
        assertSame(before, session.model)
        assertEquals(RebuildPolicy.SYNTAX_NOTICE, session.canvas.notice)
    }

    fun testUnsupportedEditKeepsLastGoodModelWithNotice() {
        val session = open()
        val before = session.model
        edit("namespace App;", "namespace App;\necho 1;")
        session.rebuildNow()
        assertSame(before, session.model)
        assertEquals("Blocks paused: File contains code before the class", session.canvas.notice)
    }

    fun testDraggedBlockIsPinned() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        assertEquals(Point(900, 900), PinStore.getInstance(project).pins(myFixture.file.virtualFile.path)["method:bar"])
        assertEquals(java.awt.Point(940, 940), session.viewBounds("method:bar")!!.location)
    }

    fun testRenamedBlockDropsItsPin() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        edit("function bar()", "function baz()")
        session.rebuildNow()
        assertFalse(PinStore.getInstance(project).pins(myFixture.file.virtualFile.path).containsKey("method:bar"))
        assertTrue(session.blockIds().contains("method:baz"))
        assertFalse(session.blockIds().contains("method:bar"))
    }

    fun testCollapseToggleReleasesAndRecreatesTheEditor() {
        val session = open()
        session.collapseToggled("method:bar")
        assertFalse(session.hasSliceEditor("method:bar"))
        session.collapseToggled("method:bar")
        assertTrue(session.hasSliceEditor("method:bar"))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.canvas.BlocksSessionTest"`
Expected: FAIL — `BlocksSession` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.readcodelikeahuman.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.readcodelikeahuman.editor.SliceEditor
import com.readcodelikeahuman.layout.LayoutEngine
import com.readcodelikeahuman.layout.Point
import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.php.PhpBlockBuilder
import com.readcodelikeahuman.settings.PinStore
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import kotlin.math.roundToInt

/** Keeps the canvas in sync with one PHP file: rebuilds the model, reconciles views and slice editors. */
class BlocksSession(private val project: Project, private val file: VirtualFile) : Disposable, BlocksCanvas.Listener {
    val canvas = BlocksCanvas(this)
    var model: BlockModel? = null
        private set

    private val document = FileDocumentManager.getInstance().getDocument(file) ?: error("No document for $file")
    private val views = linkedMapOf<String, BlockView>()
    private val slices = mutableMapOf<String, SliceEditor>()
    private val collapsed = mutableMapOf<String, Boolean>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val baseFontSize = EditorColorsManager.getInstance().globalScheme.editorFontSize

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRebuild()
        }, this)
        rebuildNow()
    }

    private fun scheduleRebuild() {
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuildNow() }, REBUILD_DELAY_MS)
    }

    fun rebuildNow() {
        if (Disposer.isDisposed(this)) return
        PsiDocumentManager.getInstance(project).commitDocument(document)
        if (DumbService.isDumb(project)) {
            scheduleRebuild()
            return
        }
        val update = ReadAction.compute<CanvasUpdate?, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            RebuildPolicy.next(model, PhpBlockBuilder.build(psi), PsiTreeUtil.hasErrorElements(psi))
        } ?: return
        canvas.notice = update.notice
        val next = update.model ?: return
        if (next != model) apply(next)
    }

    private fun apply(next: BlockModel) {
        model = next
        val live = next.blocks.map { it.id }.toSet()
        (views.keys - live).forEach { id ->
            views.remove(id)
            slices.remove(id)?.let(Disposer::dispose)
            collapsed.remove(id)
        }
        PinStore.getInstance(project).prune(file.path, live)
        for (block in next.blocks) {
            val isCollapsed = collapsed.getOrPut(block.id) { block.collapsed }
            val view = views.getOrPut(block.id) { BlockView(block.id, canvas) }
            val slice = if (isCollapsed) null else sliceFor(block)
            if (slice == null) slices.remove(block.id)?.let(Disposer::dispose)
            slice?.show(block.range, block.excluded)
            view.update(block, isCollapsed || slice == null, slice?.component ?: summaryOf(block), canvas.zoom)
        }
        canvas.setContent(LinkedHashMap(views), next.links)
        relayout()
    }

    private fun relayout() {
        val current = model ?: return
        val pins = PinStore.getInstance(project).pins(file.path)
        val layout = LayoutEngine.layout(current, { views.getValue(it.id).naturalSize(canvas.zoom) }, pins)
        canvas.place(layout.rects)
    }

    private fun sliceFor(block: Block): SliceEditor? {
        slices[block.id]?.let { return it }
        val target = if (block.filePath == file.path) file else LocalFileSystem.getInstance().findFileByPath(block.filePath) ?: return null
        val targetDocument = FileDocumentManager.getInstance().getDocument(target) ?: return null
        val slice = SliceEditor(project, target, targetDocument)
        Disposer.register(this, slice)
        slice.setFontSize(fontSize())
        slice.editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { canvas.focusedId = block.id }
        })
        slices[block.id] = slice
        return slice
    }

    private fun summaryOf(block: Block): JComponent {
        val lines = block.summary.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        return JBLabel("<html>$lines</html>").apply {
            border = JBUI.Borders.empty(4, 8)
            foreground = JBColor.GRAY
        }
    }

    private fun fontSize(): Int = (baseFontSize * canvas.zoom).roundToInt().coerceAtLeast(6)

    override fun blockMoved(id: String, position: Point) {
        PinStore.getInstance(project).pin(file.path, id, position)
        relayout()
    }

    override fun collapseToggled(id: String) {
        collapsed[id] = !(collapsed[id] ?: false)
        model?.let(::apply)
    }

    override fun zoomChanged() {
        slices.values.forEach { it.setFontSize(fontSize()) }
        model?.let(::apply)
    }

    fun blockIds(): List<String> = views.keys.toList()
    fun hasSliceEditor(id: String): Boolean = id in slices
    fun viewBounds(id: String): java.awt.Rectangle? = views[id]?.bounds

    override fun dispose() {}

    companion object {
        const val REBUILD_DELAY_MS = 300
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.canvas.*"`
Expected: PASS (7 BlocksSessionTest tests plus the earlier canvas tests). If the platform reports unreleased editors at teardown, check that every `SliceEditor` is registered with `Disposer` under the session, and that the session is registered under `testRootDisposable`.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "feat: add blocks session that keeps canvas, model and slice editors in sync"
```

---

### Task 10: Plug the canvas into PhpStorm

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/ide/BlocksFileEditor.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/ide/BlocksEditorProvider.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/ide/BlocksUnavailableNotification.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/ide/ToggleBlocksViewAction.kt`
- Create: `src/main/kotlin/com/readcodelikeahuman/ide/BlocksByDefaultAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/readcodelikeahuman/ide/BlocksEditorProviderTest.kt`

**Interfaces:**
- Consumes: `BlocksSession`, `BlocksSettings`, `PhpBlockBuilder`.
- Produces:
  - `BlocksEditorProvider` with `TYPE_ID = "read-code-blocks"`. It accepts PHP files that build as `Supported`. Its policy is `PLACE_BEFORE_DEFAULT_EDITOR` when `openAsBlocksByDefault` is on, otherwise `PLACE_AFTER_DEFAULT_EDITOR`. Not `DumbAware` (the builder needs indexes).
  - `BlocksFileEditor(project, file)`: name "Blocks", component = the session's canvas.
  - The toggle action (Ctrl+Alt+Shift+B, View menu) switches the selected editor between `TYPE_ID` and `"text-editor"`.
  - The default action is a `ToggleAction` for the setting.
  - The notification shows "Blocks view unavailable: <reason>" above the text editor of unsupported PHP files while the setting is on.

**Ruling recorded here:** PhpStorm shows "Blocks | Text" tabs at the bottom of the editor whenever two editors exist for a file. The spec asked for a *hidden* toggle. The shortcut is that toggle, and the tabs stay visible as an escape hatch the platform provides. Hiding them is deferred unless the user objects.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.readcodelikeahuman.ide

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.canvas.BlocksCanvas
import com.readcodelikeahuman.settings.BlocksSettings

class BlocksEditorProviderTest : BasePlatformTestCase() {
    private val provider = BlocksEditorProvider()

    fun testAcceptsSupportedPhpFile() {
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo {}\n").virtualFile
        assertTrue(provider.accept(project, file))
    }

    fun testRejectsUnsupportedPhpFile() {
        val file = myFixture.configureByText("Two.php", "<?php\nclass A {}\nclass B {}\n").virtualFile
        assertFalse(provider.accept(project, file))
    }

    fun testRejectsNonPhpFile() {
        val file = myFixture.configureByText("notes.txt", "hello").virtualFile
        assertFalse(provider.accept(project, file))
    }

    fun testPolicyFollowsTheSetting() {
        val state = BlocksSettings.instance.state
        val original = state.openAsBlocksByDefault
        try {
            state.openAsBlocksByDefault = true
            assertEquals(FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR, provider.policy)
            state.openAsBlocksByDefault = false
            assertEquals(FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR, provider.policy)
        } finally {
            state.openAsBlocksByDefault = original
        }
    }

    fun testCreatesBlocksEditorWithCanvas() {
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo {}\n").virtualFile
        val editor = provider.createEditor(project, file)
        try {
            assertEquals("Blocks", editor.name)
            assertInstanceOf(editor.component, BlocksCanvas::class.java)
        } finally {
            Disposer.dispose(editor)
        }
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.ide.BlocksEditorProviderTest"`
Expected: FAIL — `BlocksEditorProvider` unresolved.

- [ ] **Step 3: Implement**

`BlocksFileEditor.kt`:
```kotlin
package com.readcodelikeahuman.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.readcodelikeahuman.canvas.BlocksSession
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class BlocksFileEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val session = BlocksSession(project, file).also { Disposer.register(this, it) }

    override fun getComponent(): JComponent = session.canvas
    override fun getPreferredFocusedComponent(): JComponent = session.canvas
    override fun getName(): String = "Blocks"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
```

`BlocksEditorProvider.kt`:
```kotlin
package com.readcodelikeahuman.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.php.lang.PhpFileType
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.php.PhpBlockBuilder
import com.readcodelikeahuman.settings.BlocksSettings

class BlocksEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)) return false
        return ReadAction.compute<Boolean, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute false
            PhpBlockBuilder.build(psi) is BuildResult.Supported
        }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = BlocksFileEditor(project, file)

    override fun getEditorTypeId(): String = TYPE_ID

    override fun getPolicy(): FileEditorPolicy =
        if (BlocksSettings.instance.state.openAsBlocksByDefault) FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
        else FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID = "read-code-blocks"
    }
}
```

`BlocksUnavailableNotification.kt`:
```kotlin
package com.readcodelikeahuman.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.jetbrains.php.lang.PhpFileType
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.php.PhpBlockBuilder
import com.readcodelikeahuman.settings.BlocksSettings
import java.util.function.Function
import javax.swing.JComponent

class BlocksUnavailableNotification : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!BlocksSettings.instance.state.openAsBlocksByDefault) return null
        if (!FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)) return null
        if (DumbService.isDumb(project)) return null
        val result = ReadAction.compute<BuildResult?, RuntimeException> {
            PsiManager.getInstance(project).findFile(file)?.let(PhpBlockBuilder::build)
        }
        val reason = (result as? BuildResult.Unsupported)?.reason ?: return null
        return Function { editor ->
            if (editor !is TextEditor) null
            else EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply { text = "Blocks view unavailable: $reason" }
        }
    }
}
```

`ToggleBlocksViewAction.kt`:
```kotlin
package com.readcodelikeahuman.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware

/** The hidden toggle: switch the current PHP file between the blocks canvas and the classic text editor. */
class ToggleBlocksViewAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val manager = FileEditorManager.getInstance(project)
        val target = if (manager.getSelectedEditor(file) is BlocksFileEditor) "text-editor" else BlocksEditorProvider.TYPE_ID
        manager.setSelectedEditor(file, target)
    }
}
```

`BlocksByDefaultAction.kt`:
```kotlin
package com.readcodelikeahuman.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.EditorNotifications
import com.readcodelikeahuman.settings.BlocksSettings

class BlocksByDefaultAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = BlocksSettings.instance.state.openAsBlocksByDefault

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlocksSettings.instance.state.openAsBlocksByDefault = state
        e.project?.let { EditorNotifications.getInstance(it).updateAllNotifications() }
    }
}
```

`plugin.xml`: add inside `<idea-plugin>`, after the `<depends>` lines:
```xml
    <extensions defaultExtensionNs="com.intellij">
        <fileEditorProvider implementation="com.readcodelikeahuman.ide.BlocksEditorProvider"/>
        <editorNotificationProvider implementation="com.readcodelikeahuman.ide.BlocksUnavailableNotification"/>
    </extensions>

    <actions>
        <action id="ReadCode.ToggleBlocksView"
                class="com.readcodelikeahuman.ide.ToggleBlocksViewAction"
                text="Toggle Blocks / Text View">
            <keyboard-shortcut keymap="$default" first-keystroke="control alt shift B"/>
            <add-to-group group-id="ViewMenu" anchor="last"/>
        </action>
        <action id="ReadCode.BlocksByDefault"
                class="com.readcodelikeahuman.ide.BlocksByDefaultAction"
                text="Open PHP Files as Blocks by Default">
            <add-to-group group-id="ViewMenu" anchor="last"/>
        </action>
    </actions>
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS (everything, including 5 BlocksEditorProviderTest tests).

- [ ] **Step 5: Build the plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL; zip in `build/distributions/`.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "feat: open supported PHP files as a blocks canvas in PhpStorm"
```

---

### Task 11: Manual verification in the sandbox IDE

Automated tests cover logic, not feel. This task is done by the human partner with the agent guiding.

**Files:**
- Create: `docs/superpowers/research/2026-10-08-canvas-see-and-edit-checklist.md` (results)

- [ ] **Step 1: Start the sandbox**

Run: `./gradlew runIde`. Reuse the `/tmp/slice-test` project (`Foo.php`, `Big.php`) and add a `Barro.php` interface that `Foo` implements.

- [ ] **Step 2: Walk the checklist and record yes/no + notes**

1. Opening `Foo.php` shows the canvas: `Barro` on the left, header above `Foo` in the middle, methods on the right, with lines between them.
2. Typing in a method edits the real file (Toggle Blocks / Text View with Ctrl+Alt+Shift+B shows the change).
3. In the class block there's no gap where the methods were, and nowhere to type that lands on a method line (ghost typing gone).
4. Typing a complete new method in the class block makes it pop out as a new block after ~0.3s.
5. Half-typed code shows "Fix the syntax errors…" and the blocks don't jump.
6. Dragging a block moves it; reopening the file (or restarting the sandbox) keeps the position.
7. Dragging the background pans; scroll pans; Cmd+scroll zooms (fonts scale, layout stays sane).
8. Clicking a title collapses/expands; `Barro` starts collapsed showing `bar()`, and expanding it shows its editable code.
9. Clicking into `bar` shows the `calls` line to `footest`.
10. `Big.php` with 10+ methods: the method column wraps when tall, and it stays responsive.
11. A PHP file with two classes opens as text with "Blocks view unavailable: …".
12. Scrolling over a block's code (not the title) still pans the canvas.

- [ ] **Step 3: Record results and commit**

Write the results into the checklist file (same format as the spike findings: verdict, per-item yes/no + note, follow-ups for plan 2).

```bash
git add docs
git commit -m "docs: record canvas see-and-edit manual verification"
```
