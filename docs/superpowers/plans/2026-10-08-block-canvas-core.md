# Block Canvas Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold the JetBrains plugin, prove (throwaway spike) that a real editor can show and edit a slice of a PHP file, and build the tested, UI-free core: Block Model, Layout Engine and PHP Block Builder.

**Architecture:** A pure-Kotlin Block Model (blocks + links, no platform types) is produced by a PHP Block Builder that reads PhpStorm's PSI and relies on PSI reference resolution for everything cross-file. A pure Layout Engine turns the model into deterministic three-column positions and arrow anchors. The Canvas Editor (UI) is **not** in this plan: it gets its own plan after the Task 2 spike, because its implementation depends on the spike's findings.

**Tech Stack:** Kotlin 2.2, IntelliJ Platform Gradle Plugin 2.x, PhpStorm 2026.1 platform + bundled PHP plugin (`com.jetbrains.php`), JUnit 4 / IntelliJ `BasePlatformTestCase`, Gradle with JDK 21 toolchain.

**Spec:** `docs/superpowers/specs/2026-10-08-block-canvas-editor-design.md`

## Global Constraints

- Kotlin, IntelliJ Platform Gradle Plugin; plugin depends on `com.jetbrains.php`.
- Target PhpStorm 2025.x+ (`sinceBuild = "251"`); build/test against the locally installed PhpStorm 2026.1.
- Fully local: no network calls, no AI, at runtime.
- The file on disk is the single source of truth; the model never stores code text, only ranges.
- v1 supports only PHP files with exactly one class / interface / trait / enum; everything else is `Unsupported(reason)`.
- Cross-file references are resolved only through PSI (`resolve()` / PSI accessors). Unresolved ⇒ no block, no custom fallback logic.
- Base package: `com.readcodelikeahuman`.

## Review Focus

1. **Code outside the class** (loose statements or functions after/around the class): must yield `Unsupported`, never silently disappear from the canvas. → Task 5 `testCodeAfterClassIsUnsupported`, `testTopLevelFunctionIsUnsupported`.
2. **Invisible code:** every non-whitespace character of a supported file must be visible in exactly one block (range minus excluded ranges). → Task 5 `testEveryNonWhitespaceCharIsInExactlyOneBlock`.
3. **Doc comments and attributes** on methods must travel with their method block, not stay behind in the class block. → Task 5 `testDocCommentAndAttributeBelongToMethodBlock`.
4. **Unresolved references** (interface/parent/dependency not in the project): no block, no crash, title still shows the name. → Task 6 `testUnresolvedInterfaceGetsNoBlock`, Task 7 `testUnresolvedDependencyGetsNoBlock`.
5. **Calls that only look internal** (`$this->other->bar()`, recursion, trait methods): must not produce method→method arrows. → Task 8 `testCallsIgnoreOtherObjectsAndRecursion`.

## PSI API note (applies to Tasks 5–8)

PHP PSI classes live in `com.jetbrains.php.lang.psi` and `com.jetbrains.php.lang.psi.elements`. If an accessor named in this plan does not compile against 2026.1, find the equivalent by running `./gradlew runIde`, opening a PHP file, and using **Tools → View PSI Structure of Current File**, plus IDE completion on the PSI class. Keep the tests unchanged; they define the behavior.

## File Structure

```
settings.gradle.kts                      Gradle settings + JDK toolchain resolver
build.gradle.kts                         Plugin build, PhpStorm platform, test deps
gradle.properties                        Kotlin stdlib opt-out
.gitignore
src/main/resources/META-INF/plugin.xml   Plugin descriptor
src/main/kotlin/com/readcodelikeahuman/
  model/BlockModel.kt                    Block, Link, BlockModel, BuildResult, SourceRange (pure)
  layout/LayoutEngine.kt                 Size, Rect, Point, Arrow, Layout, LayoutEngine (pure)
  php/PhpBlockBuilder.kt                 PSI → BuildResult
  spike/…                                Task 2 only, deleted at end of Task 2
src/test/kotlin/com/readcodelikeahuman/
  SmokeTest.kt
  model/BlockModelTest.kt
  layout/LayoutEngineTest.kt
  php/PhpBuilderTestCase.kt              shared helpers
  php/PhpBlockBuilderStructureTest.kt    Task 5
  php/PhpBlockBuilderRelatedTypesTest.kt Task 6
  php/PhpBlockBuilderDependencyTest.kt   Task 7
  php/PhpBlockBuilderCallsTest.kt        Task 8
docs/superpowers/spikes/2026-10-08-slice-editor-findings.md   Task 2 output
```

---

### Task 1: Plugin scaffold with PHP smoke test

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`
- Create: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/readcodelikeahuman/SmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a building Gradle project where `./gradlew test` runs IntelliJ platform tests with the PHP plugin loaded.

- [ ] **Step 1: Install tooling**

Run: `brew install gradle` (pulls in an OpenJDK so Gradle can run; the JDK 21 toolchain for compilation is auto-downloaded by the foojay resolver).
Expected: `gradle --version` prints a Gradle 9.x version.

- [ ] **Step 2: Write Gradle files**

`settings.gradle.kts`:
```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "read-code-like-a-human"
```

`build.gradle.kts`:
```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    // Use the latest 2.x from https://plugins.gradle.org/plugin/org.jetbrains.intellij.platform
    // if this version does not resolve.
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "com.readcodelikeahuman"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        phpstorm("2026.1")
        bundledPlugin("com.jetbrains.php")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
    }
}
```

`gradle.properties`:
```properties
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
```

`.gitignore`:
```
.gradle/
build/
.idea/
.intellijPlatform/
.kotlin/
*.iml
```

- [ ] **Step 3: Write plugin descriptor**

`src/main/resources/META-INF/plugin.xml`:
```xml
<idea-plugin>
    <id>com.readcodelikeahuman.blocks</id>
    <name>Read Code Like a Human</name>
    <vendor email="rtm.gerrits@gmail.com">Rene Gerrits</vendor>
    <description><![CDATA[
        Shows code as editable blocks on a 2D canvas instead of a vertical
        line-by-line editor, so humans really read and understand the code.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.jetbrains.php</depends>
</idea-plugin>
```

- [ ] **Step 4: Generate the wrapper**

Run: `gradle wrapper`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` created.

- [ ] **Step 5: Write the smoke test**

`src/test/kotlin/com/readcodelikeahuman/SmokeTest.kt`:
```kotlin
package com.readcodelikeahuman

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.PhpFile

class SmokeTest : BasePlatformTestCase() {
    fun testPhpPluginParsesPhpFiles() {
        val file = myFixture.configureByText("A.php", "<?php\nclass A {}\n")
        assertTrue("Expected PhpFile but got ${file.javaClass}", file is PhpFile)
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew test --tests "com.readcodelikeahuman.SmokeTest"`
Expected: PASS (first run downloads PhpStorm 2026.1; this takes a while).
If it fails with a missing class / test framework error, fix the build file (not the test) until it passes.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore gradlew gradlew.bat gradle src
git commit -m "chore: scaffold PhpStorm plugin with PHP smoke test"
```

---

### Task 2: SPIKE — slice editor feasibility (throwaway code)

Answers one question: **can a real IntelliJ editor show and edit only a range of a PHP document (with some inner ranges hidden), with completion and highlighting working and edits landing in the real file?**

Technique under test: create a normal editor on the real `Document` with `EditorFactory.createEditor(document, project, file, false)`, then **fold away** everything outside the slice (and the hidden inner ranges) with empty-placeholder fold regions, and clamp the caret to the slice.

All code in this task lives in `spike/` and is deleted in the last step. Only the findings document is kept.

**Files:**
- Create (throwaway): `src/main/kotlin/com/readcodelikeahuman/spike/SliceEditorSpike.kt`
- Create (throwaway): `src/main/kotlin/com/readcodelikeahuman/spike/SliceEditorSpikeAction.kt`
- Create (throwaway): `src/test/kotlin/com/readcodelikeahuman/spike/SliceEditorSpikeTest.kt`
- Modify (temporarily): `src/main/resources/META-INF/plugin.xml`
- Create (kept): `docs/superpowers/spikes/2026-10-08-slice-editor-findings.md`

**Interfaces:**
- Consumes: Task 1 scaffold.
- Produces: the findings document with a go / no-go for the "fold-based slice editor" technique. No code is consumed by later tasks.

- [ ] **Step 1: Write the spike tests**

`src/test/kotlin/com/readcodelikeahuman/spike/SliceEditorSpikeTest.kt`:
```kotlin
package com.readcodelikeahuman.spike

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.Method

class SliceEditorSpikeTest : BasePlatformTestCase() {
    private val source = """
        <?php
        class Foo {
            private string ${'$'}name = 'x';

            public function bar(): void {
                ${'$'}this-><caret>
            }

            public function footest(string ${'$'}s): void {
            }
        }
    """.trimIndent()

    private fun method(name: String): Method =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Method::class.java).first { it.name == name }

    fun testSliceFoldsEverythingOutsideRangeAndEditsRealDocument() {
        myFixture.configureByText("Foo.php", source)
        val document = myFixture.editor.document
        val range = method("footest").textRange
        val editor = SliceEditorSpike.create(project, myFixture.file.virtualFile, document, range, emptyList())
        try {
            val folds = editor.foldingModel.allFoldRegions
            assertEquals(2, folds.size)
            assertTrue(folds.none { it.isExpanded })

            WriteCommandAction.runWriteCommandAction(project) {
                editor.caretModel.moveToOffset(range.endOffset - 1)
                EditorModificationUtil.insertStringAtCaret(editor, "echo 1;")
            }
            assertTrue(document.text.contains("echo 1;}"))

            editor.caretModel.moveToOffset(0)
            assertEquals(range.startOffset, editor.caretModel.offset)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testClassSliceHidesInnerMethodRanges() {
        myFixture.configureByText("Foo.php", source)
        val document = myFixture.editor.document
        val classRange = TextRange(source.indexOf("class Foo"), myFixture.file.textLength)
        val hidden = listOf(method("bar").textRange, method("footest").textRange)
        val editor = SliceEditorSpike.create(project, myFixture.file.virtualFile, document, classRange, hidden)
        try {
            assertEquals(3, editor.foldingModel.allFoldRegions.size)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testCompletionWorksInFoldedEditor() {
        myFixture.configureByText("Foo.php", source)
        SliceEditorSpike.applyFolds(myFixture.editor as EditorEx, method("bar").textRange, emptyList())
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("lookup was $items", items.contains("footest"))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.spike.*"`
Expected: FAIL — compilation error, `SliceEditorSpike` unresolved.

- [ ] **Step 3: Implement the spike editor**

`src/main/kotlin/com/readcodelikeahuman/spike/SliceEditorSpike.kt`:
```kotlin
package com.readcodelikeahuman.spike

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

object SliceEditorSpike {
    fun create(
        project: Project,
        file: VirtualFile,
        document: Document,
        range: TextRange,
        hidden: List<TextRange>,
    ): EditorEx {
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

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.spike.*"`
Expected: PASS. If a test fails, record **why** in the findings (e.g. "empty placeholder rejected", "caret clamp doesn't fire on programmatic moves") and try at most one variation per failure. Do not spend more than ~1 hour total on fixes; a well-documented failure is a valid spike result.

- [ ] **Step 5: Add a manual-check action**

`src/main/kotlin/com/readcodelikeahuman/spike/SliceEditorSpikeAction.kt`:
```kotlin
package com.readcodelikeahuman.spike

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpClass
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SliceEditorSpikeAction : AnAction("Spike: Show Slice Editors") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PhpFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java) ?: return
        SliceDialog(project, psiFile.virtualFile, document, phpClass).show()
    }
}

private class SliceDialog(
    private val project: Project,
    private val file: VirtualFile,
    private val document: Document,
    private val phpClass: PhpClass,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val editors = mutableListOf<EditorEx>()

    init {
        title = "Slice Editor Spike"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val methods = phpClass.ownMethods.filterNot { it.name.equals("__construct", ignoreCase = true) }
        editors += SliceEditorSpike.create(project, file, document, phpClass.textRange, methods.map { it.textRange })
        methods.forEach { editors += SliceEditorSpike.create(project, file, document, it.textRange, emptyList()) }

        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        editors.forEach { editor ->
            editor.component.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            panel.add(editor.component)
        }
        return JBScrollPane(panel).apply { preferredSize = Dimension(900, 700) }
    }

    override fun dispose() {
        editors.forEach { EditorFactory.getInstance().releaseEditor(it) }
        super.dispose()
    }
}
```

Register it in `plugin.xml` (inside `<idea-plugin>`):
```xml
    <actions>
        <action id="ReadCode.SliceSpike"
                class="com.readcodelikeahuman.spike.SliceEditorSpikeAction"
                text="Spike: Show Slice Editors">
            <add-to-group group-id="ToolsMenu" anchor="last"/>
        </action>
    </actions>
```

- [ ] **Step 6: Manual check in the sandbox IDE**

Run: `./gradlew runIde`. In the sandbox, create a project with a PHP class that has a property, a constructor and 2–3 methods (one calling another via `$this->`). Open it next to the dialog (**Tools → Spike: Show Slice Editors**) and check each item, writing yes/no + notes:

1. Class slice shows header/properties/constructor, without the methods; each method slice shows only its method.
2. Typing in a method slice changes the real file (visible in the normal editor).
3. `$this->` completion popup appears, lists class methods, and is positioned correctly.
4. Semantic highlighting/inspections appear in the slice (e.g. call an undefined method → warning).
5. Adding new lines at the end of a method slice keeps them visible (fold boundary does not swallow them).
6. Undo (Cmd+Z) in a slice reverts the edit.
7. Caret cannot escape into hidden regions (Up at first line, Down at last line, Cmd+Home/End).
8. Editing in the normal editor updates the slices; two slices stay consistent.
9. Ctrl/Cmd+click navigation from a slice works.
10. Rough feel: does a dialog with ~10 slice editors stay responsive?

- [ ] **Step 7: Write the findings**

`docs/superpowers/spikes/2026-10-08-slice-editor-findings.md` with exactly these sections:
```markdown
# Slice Editor Spike — Findings

## Verdict
GO / GO WITH WORKAROUNDS / NO-GO for fold-based slice editors.

## Automated tests
<pass/fail per test, and what was changed to make them pass>

## Manual checklist
<items 1–10, each yes/no + one-line note>

## Workarounds needed
<each problem found and the fix or idea; "none" if none>

## Technique to carry forward
<the final create/applyFolds code that worked, pasted in full, so the canvas plan can reuse it>

## Recommendation for the canvas plan
<approach A as designed, or fallback C (read-only blocks, double-click to edit), and why>
```

- [ ] **Step 8: Delete the spike code, keep the findings**

Delete `src/main/kotlin/com/readcodelikeahuman/spike/`, `src/test/kotlin/com/readcodelikeahuman/spike/`, and the `<actions>` block from `plugin.xml`.
Run: `./gradlew test`
Expected: PASS (only `SmokeTest` remains).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "docs: record slice editor spike findings"
```

- [ ] **Step 10: GATE — report to the human partner**

Stop and report the verdict and recommendation. Tasks 3–8 do not depend on the verdict, so continue with them unless told otherwise. The canvas plan is written only after this report.

---

### Task 3: Block Model

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/model/BlockModel.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/model/BlockModelTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin, no platform imports).
- Produces:
  - `enum class BlockKind { HEADER, INTERFACE, PARENT, TRAIT, DEPENDENCY, CLASS, METHOD }`
  - `enum class LinkKind { IMPLEMENTS, EXTENDS, USES, INJECTS, OWNS, CALLS }`
  - `data class SourceRange(val start: Int, val end: Int)` — half-open `[start, end)`, `contains(offset: Int): Boolean`
  - `data class Block(val id: String, val kind: BlockKind, val title: String, val filePath: String, val range: SourceRange, val excluded: List<SourceRange> = emptyList(), val collapsed: Boolean = false)`
  - `data class Link(val kind: LinkKind, val from: String, val to: String)`
  - `data class BlockModel(val blocks: List<Block>, val links: List<Link>)` with `fun block(id: String): Block` and `fun ofKind(kind: BlockKind): List<Block>`
  - `sealed interface BuildResult { data class Supported(val model: BlockModel); data class Unsupported(val reason: String) }`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/readcodelikeahuman/model/BlockModelTest.kt`:
```kotlin
package com.readcodelikeahuman.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockModelTest {
    private fun block(id: String, kind: BlockKind, range: SourceRange = SourceRange(0, 1)) =
        Block(id = id, kind = kind, title = id, filePath = "/Foo.php", range = range)

    @Test
    fun sourceRangeIsHalfOpen() {
        val r = SourceRange(2, 5)
        assertTrue(r.contains(2))
        assertTrue(r.contains(4))
        assertFalse(r.contains(5))
    }

    @Test
    fun sourceRangeRejectsNegativeOrInverted() {
        assertThrows(IllegalArgumentException::class.java) { SourceRange(-1, 3) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange(5, 3) }
    }

    @Test
    fun excludedRangesMustBeInsideBlockRange() {
        assertThrows(IllegalArgumentException::class.java) {
            Block("class:Foo", BlockKind.CLASS, "class Foo", "/Foo.php", SourceRange(10, 20), excluded = listOf(SourceRange(5, 12)))
        }
    }

    @Test
    fun blockIdsMustBeUnique() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockModel(listOf(block("a", BlockKind.METHOD), block("a", BlockKind.METHOD)), emptyList())
        }
    }

    @Test
    fun linksMustPointToExistingBlocks() {
        assertThrows(IllegalArgumentException::class.java) {
            BlockModel(listOf(block("a", BlockKind.CLASS)), listOf(Link(LinkKind.OWNS, "a", "missing")))
        }
    }

    @Test
    fun lookupByIdAndKindKeepsOrder() {
        val model = BlockModel(
            listOf(block("class:Foo", BlockKind.CLASS), block("method:b", BlockKind.METHOD), block("method:a", BlockKind.METHOD)),
            emptyList(),
        )
        assertEquals(BlockKind.CLASS, model.block("class:Foo").kind)
        assertEquals(listOf("method:b", "method:a"), model.ofKind(BlockKind.METHOD).map { it.id })
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.model.BlockModelTest"`
Expected: FAIL — compilation errors, model types unresolved.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/readcodelikeahuman/model/BlockModel.kt`:
```kotlin
package com.readcodelikeahuman.model

enum class BlockKind { HEADER, INTERFACE, PARENT, TRAIT, DEPENDENCY, CLASS, METHOD }

enum class LinkKind { IMPLEMENTS, EXTENDS, USES, INJECTS, OWNS, CALLS }

/** Half-open character range `[start, end)` in a file. */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start >= 0 && start <= end) { "Invalid range [$start, $end)" }
    }

    fun contains(offset: Int): Boolean = offset >= start && offset < end
}

/**
 * One block on the canvas: a slice of [filePath] covering [range], minus [excluded]
 * sub-ranges that are shown by other blocks (e.g. methods inside the class block).
 */
data class Block(
    val id: String,
    val kind: BlockKind,
    val title: String,
    val filePath: String,
    val range: SourceRange,
    val excluded: List<SourceRange> = emptyList(),
    val collapsed: Boolean = false,
) {
    init {
        require(excluded.all { it.start >= range.start && it.end <= range.end }) {
            "Excluded ranges of $id must lie inside $range"
        }
    }
}

data class Link(val kind: LinkKind, val from: String, val to: String)

data class BlockModel(val blocks: List<Block>, val links: List<Link>) {
    private val byId: Map<String, Block> = blocks.associateBy { it.id }

    init {
        require(byId.size == blocks.size) { "Block ids must be unique" }
        links.forEach { require(it.from in byId && it.to in byId) { "Link $it points to a missing block" } }
    }

    fun block(id: String): Block = byId.getValue(id)

    fun ofKind(kind: BlockKind): List<Block> = blocks.filter { it.kind == kind }
}

sealed interface BuildResult {
    data class Supported(val model: BlockModel) : BuildResult
    data class Unsupported(val reason: String) : BuildResult
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.model.BlockModelTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/readcodelikeahuman/model src/test/kotlin/com/readcodelikeahuman/model
git commit -m "feat: add language-agnostic block model"
```

---

### Task 4: Layout Engine

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/layout/LayoutEngine.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/layout/LayoutEngineTest.kt`

**Interfaces:**
- Consumes: `Block`, `BlockKind`, `BlockModel`, `Link`, `LinkKind`, `SourceRange` from Task 3.
- Produces:
  - `data class Size(val width: Int, val height: Int)`
  - `data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)` with `right`, `centerY`
  - `data class Point(val x: Int, val y: Int)`
  - `data class Arrow(val link: Link, val from: Point, val to: Point)`
  - `data class Layout(val rects: Map<String, Rect>, val arrows: List<Arrow>)`
  - `object LayoutEngine { const val H_GAP = 80; const val V_GAP = 24; fun layout(model: BlockModel, sizeOf: (Block) -> Size): Layout }`
  - Rules: columns left (INTERFACE, PARENT, TRAIT, DEPENDENCY, in that kind order, model order within kind), center (HEADER then CLASS), right (METHOD, model order). Empty columns take no space. Each column starts at y = 0. Arrows go from source right-middle to target left-middle; CALLS go right-middle to right-middle.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/readcodelikeahuman/layout/LayoutEngineTest.kt`:
```kotlin
package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import com.readcodelikeahuman.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutEngineTest {
    private fun block(id: String, kind: BlockKind) =
        Block(id = id, kind = kind, title = id, filePath = "/Foo.php", range = SourceRange(0, 1))

    private val sizeOf: (Block) -> Size = { if (it.kind == BlockKind.CLASS) Size(200, 120) else Size(100, 50) }

    private val fullModel = BlockModel(
        blocks = listOf(
            block("dependency:Repo", BlockKind.DEPENDENCY),
            block("header", BlockKind.HEADER),
            block("class:Foo", BlockKind.CLASS),
            block("method:bar", BlockKind.METHOD),
            block("method:footest", BlockKind.METHOD),
            block("interface:Barro", BlockKind.INTERFACE),
        ),
        links = listOf(
            Link(LinkKind.IMPLEMENTS, "interface:Barro", "class:Foo"),
            Link(LinkKind.INJECTS, "dependency:Repo", "class:Foo"),
            Link(LinkKind.OWNS, "class:Foo", "method:bar"),
            Link(LinkKind.CALLS, "method:bar", "method:footest"),
        ),
    )

    @Test
    fun placesBlocksInThreeColumns() {
        val rects = LayoutEngine.layout(fullModel, sizeOf).rects
        // left column: interfaces before dependencies, regardless of model order
        assertEquals(Rect(0, 0, 100, 50), rects["interface:Barro"])
        assertEquals(Rect(0, 74, 100, 50), rects["dependency:Repo"])
        // center column at x = 100 + 80
        assertEquals(Rect(180, 0, 100, 50), rects["header"])
        assertEquals(Rect(180, 74, 200, 120), rects["class:Foo"])
        // right column at x = 180 + 200 + 80
        assertEquals(Rect(460, 0, 100, 50), rects["method:bar"])
        assertEquals(Rect(460, 74, 100, 50), rects["method:footest"])
    }

    @Test
    fun anchorsArrowsOnBlockEdges() {
        val arrows = LayoutEngine.layout(fullModel, sizeOf).arrows.associate { it.link.kind to (it.from to it.to) }
        assertEquals(Point(100, 25) to Point(180, 134), arrows[LinkKind.IMPLEMENTS])
        assertEquals(Point(100, 99) to Point(180, 134), arrows[LinkKind.INJECTS])
        assertEquals(Point(380, 134) to Point(460, 25), arrows[LinkKind.OWNS])
        assertEquals(Point(560, 25) to Point(560, 99), arrows[LinkKind.CALLS])
    }

    @Test
    fun emptyLeftColumnTakesNoSpace() {
        val model = BlockModel(
            listOf(block("class:Foo", BlockKind.CLASS), block("method:bar", BlockKind.METHOD)),
            emptyList(),
        )
        val rects = LayoutEngine.layout(model, sizeOf).rects
        assertEquals(Rect(0, 0, 200, 120), rects["class:Foo"])
        assertEquals(Rect(280, 0, 100, 50), rects["method:bar"])
    }

    @Test
    fun isDeterministic() {
        assertEquals(LayoutEngine.layout(fullModel, sizeOf), LayoutEngine.layout(fullModel, sizeOf))
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.layout.LayoutEngineTest"`
Expected: FAIL — compilation errors, layout types unresolved.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/readcodelikeahuman/layout/LayoutEngine.kt`:
```kotlin
package com.readcodelikeahuman.layout

import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

data class Size(val width: Int, val height: Int)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val centerY: Int get() = y + height / 2
}

data class Point(val x: Int, val y: Int)

data class Arrow(val link: Link, val from: Point, val to: Point)

data class Layout(val rects: Map<String, Rect>, val arrows: List<Arrow>)

/** Deterministic three-column layout: related types | header + class | methods. */
object LayoutEngine {
    const val H_GAP = 80
    const val V_GAP = 24

    private val LEFT_KINDS = listOf(BlockKind.INTERFACE, BlockKind.PARENT, BlockKind.TRAIT, BlockKind.DEPENDENCY)
    private val CENTER_KINDS = listOf(BlockKind.HEADER, BlockKind.CLASS)
    private val RIGHT_KINDS = listOf(BlockKind.METHOD)

    fun layout(model: BlockModel, sizeOf: (Block) -> Size): Layout {
        val columns = listOf(LEFT_KINDS, CENTER_KINDS, RIGHT_KINDS).map { kinds -> kinds.flatMap(model::ofKind) }
        val rects = linkedMapOf<String, Rect>()
        var x = 0
        for (column in columns) {
            if (column.isEmpty()) continue
            var y = 0
            var width = 0
            for (block in column) {
                val size = sizeOf(block)
                rects[block.id] = Rect(x, y, size.width, size.height)
                y += size.height + V_GAP
                width = maxOf(width, size.width)
            }
            x += width + H_GAP
        }
        val arrows = model.links.map { link ->
            val from = rects.getValue(link.from)
            val to = rects.getValue(link.to)
            val end = if (link.kind == LinkKind.CALLS) Point(to.right, to.centerY) else Point(to.x, to.centerY)
            Arrow(link, Point(from.right, from.centerY), end)
        }
        return Layout(rects, arrows)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.layout.LayoutEngineTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/readcodelikeahuman/layout src/test/kotlin/com/readcodelikeahuman/layout
git commit -m "feat: add deterministic three-column layout engine"
```

---

### Task 5: PHP Block Builder — support check, header, class and method blocks

**Files:**
- Create: `src/main/kotlin/com/readcodelikeahuman/php/PhpBlockBuilder.kt`
- Create: `src/test/kotlin/com/readcodelikeahuman/php/PhpBuilderTestCase.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderStructureTest.kt`

**Interfaces:**
- Consumes: Task 3 model types.
- Produces:
  - `object PhpBlockBuilder { fun build(file: PsiFile): BuildResult }`
  - Block ids: `"header"`, `"class:<FQN>"` (e.g. `class:\App\Service\Foo`), `"method:<name>"`.
  - Titles: header `"namespace App\Service · 1 imports"` (or `"global namespace · N imports"`); class `"<keyword> <Name>[ extends A][ implements B, C]"` where keyword ∈ class/interface/trait/enum; method `"<name>(<params>)"` with each param's text before any `=` default, e.g. `footest(string $s)`.
  - Header and class blocks: header `collapsed = true`; class and methods `collapsed = false`.
  - Class block `excluded` = the method block ranges. `__construct` stays in the class block.
  - Block ranges include the element's doc comment.
  - Every method gets an `OWNS` link from the class block.
  - Test helpers in `PhpBuilderTestCase`: `build(text, fileName = "Foo.php")`, `supported(text)`, `addPhp(path, text)`.

- [ ] **Step 1: Write the shared test base**

`src/test/kotlin/com/readcodelikeahuman/php/PhpBuilderTestCase.kt`:
```kotlin
package com.readcodelikeahuman.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult

abstract class PhpBuilderTestCase : BasePlatformTestCase() {
    protected fun build(text: String, fileName: String = "Foo.php"): BuildResult =
        PhpBlockBuilder.build(myFixture.configureByText(fileName, text))

    protected fun supported(text: String): BlockModel = when (val result = build(text)) {
        is BuildResult.Supported -> result.model
        is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
    }

    protected fun addPhp(path: String, text: String) {
        myFixture.addFileToProject(path, text)
    }

    /** The text of [block] as shown on the canvas: its range minus its excluded ranges. */
    protected fun visibleText(block: Block): String {
        val text = myFixture.file.text
        return (block.range.start until block.range.end)
            .filter { offset -> block.excluded.none { it.contains(offset) } }
            .map { text[it] }
            .joinToString("")
    }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderStructureTest.kt`:
```kotlin
package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderStructureTest : PhpBuilderTestCase() {
    private val foo = """
        <?php
        namespace App\Service;

        use App\Contract\Barro;

        class Foo implements Barro
        {
            private const MAX = 3;
            private string ${'$'}name;

            public function __construct(string ${'$'}name)
            {
                ${'$'}this->name = ${'$'}name;
            }

            public function bar(): void
            {
            }

            public function footest(string ${'$'}s = 'x'): string
            {
                return ${'$'}s;
            }
        }
    """.trimIndent() + "\n"

    fun testNonPhpFileIsUnsupported() {
        assertInstanceOf(build("hello", "notes.txt"), BuildResult.Unsupported::class.java)
    }

    fun testProceduralFileIsUnsupported() {
        val result = build("<?php\necho 'hi';\n")
        assertEquals(BuildResult.Unsupported("Expected exactly one class, interface, trait or enum, found 0"), result)
    }

    fun testTwoClassesAreUnsupported() {
        val result = build("<?php\nclass A {}\nclass B {}\n")
        assertEquals(BuildResult.Unsupported("Expected exactly one class, interface, trait or enum, found 2"), result)
    }

    fun testTopLevelFunctionIsUnsupported() {
        val result = build("<?php\nfunction helper() {}\nclass A {}\n")
        assertEquals(BuildResult.Unsupported("File contains top-level functions"), result)
    }

    fun testCodeAfterClassIsUnsupported() {
        val result = build("<?php\nclass A {}\necho 'loose';\n")
        assertEquals(BuildResult.Unsupported("File contains code after the class"), result)
    }

    fun testAnonymousClassInsideMethodIsStillSupported() {
        val model = supported("<?php\nclass A {\n    public function make() { return new class {}; }\n}\n")
        assertEquals(listOf("method:make"), model.ofKind(BlockKind.METHOD).map { it.id })
    }

    fun testHeaderClassAndMethodBlocks() {
        val model = supported(foo)

        val header = model.block("header")
        assertEquals("namespace App\\Service · 1 imports", header.title)
        assertTrue(header.collapsed)
        assertTrue(visibleText(header).contains("use App\\Contract\\Barro;"))

        val cls = model.block("class:\\App\\Service\\Foo")
        assertEquals(BlockKind.CLASS, cls.kind)
        assertEquals("class Foo implements Barro", cls.title)
        assertFalse(cls.collapsed)
        val classText = visibleText(cls)
        assertTrue(classText.contains("private const MAX = 3;"))
        assertTrue(classText.contains("public function __construct"))
        assertFalse(classText.contains("function bar"))
        assertFalse(classText.contains("function footest"))

        val methods = model.ofKind(BlockKind.METHOD)
        assertEquals(listOf("method:bar", "method:footest"), methods.map { it.id })
        assertEquals(listOf("bar()", "footest(string \$s)"), methods.map { it.title })
        assertTrue(visibleText(methods[1]).startsWith("public function footest"))

        val owns = model.links.filter { it.kind == LinkKind.OWNS }.map { it.from to it.to }
        assertEquals(
            listOf(cls.id to "method:bar", cls.id to "method:footest"),
            owns,
        )
    }

    fun testDocCommentAndAttributeBelongToMethodBlock() {
        val model = supported(
            """
            <?php
            class A
            {
                /** Does the thing. */
                #[\Deprecated]
                public function thing(): void {}
            }
            """.trimIndent() + "\n",
        )
        val method = visibleText(model.block("method:thing"))
        assertTrue(method, method.startsWith("/** Does the thing. */"))
        assertTrue(method, method.contains("#[\\Deprecated]"))
        assertFalse(visibleText(model.ofKind(BlockKind.CLASS).single()).contains("Does the thing"))
    }

    fun testEveryNonWhitespaceCharIsInExactlyOneBlock() {
        val model = supported(foo)
        val text = myFixture.file.text
        val counts = IntArray(text.length)
        model.blocks.filter { it.filePath == myFixture.file.virtualFile.path }.forEach { block ->
            (block.range.start until block.range.end)
                .filter { offset -> block.excluded.none { it.contains(offset) } }
                .forEach { counts[it]++ }
        }
        text.indices.filter { !text[it].isWhitespace() }.forEach {
            assertEquals("offset $it '${text[it]}'", 1, counts[it])
        }
    }

    fun testInterfaceFileUsesSameLayout() {
        val model = supported("<?php\ninterface Barro\n{\n    public function bar(): void;\n}\n")
        assertEquals("interface Barro", model.ofKind(BlockKind.CLASS).single().title)
        assertEquals(listOf("bar()"), model.ofKind(BlockKind.METHOD).map { it.title })
    }

    fun testEnumCasesStayInCenterBlock() {
        val model = supported(
            "<?php\nenum Suit: string\n{\n    case Hearts = 'H';\n    public function label(): string { return 'x'; }\n}\n",
        )
        val center = model.ofKind(BlockKind.CLASS).single()
        assertEquals("enum Suit", center.title)
        assertTrue(visibleText(center).contains("case Hearts = 'H';"))
        assertEquals(listOf("method:label"), model.ofKind(BlockKind.METHOD).map { it.id })
    }
}
```

- [ ] **Step 3: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.PhpBlockBuilderStructureTest"`
Expected: FAIL — compilation error, `PhpBlockBuilder` unresolved.

- [ ] **Step 4: Implement**

`src/main/kotlin/com/readcodelikeahuman/php/PhpBlockBuilder.kt`:
```kotlin
package com.readcodelikeahuman.php

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpUse
import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import com.readcodelikeahuman.model.SourceRange
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction

/** Turns a single-type PHP file into a [BlockModel]; anything else is [BuildResult.Unsupported]. */
object PhpBlockBuilder {
    const val HEADER_ID = "header"

    fun build(file: PsiFile): BuildResult {
        if (file !is PhpFile) return BuildResult.Unsupported("Not a PHP file")

        val classes = PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).filterNot { it.isAnonymous }
        if (classes.size != 1) {
            return BuildResult.Unsupported("Expected exactly one class, interface, trait or enum, found ${classes.size}")
        }
        val phpClass = classes.single()

        val functions = PsiTreeUtil.findChildrenOfType(file, PhpFunction::class.java)
            .filter { it !is Method && !it.isClosure }
        if (functions.isNotEmpty()) return BuildResult.Unsupported("File contains top-level functions")

        val classRange = rangeWithDoc(phpClass)
        if (file.text.substring(classRange.end).isNotBlank()) {
            return BuildResult.Unsupported("File contains code after the ${keyword(phpClass)}")
        }

        val path = file.virtualFile.path
        val classId = "class:${phpClass.fqn}"
        val methods = phpClass.ownMethods
            .filterNot { it.name.equals("__construct", ignoreCase = true) }
            .sortedBy { it.textRange.startOffset }
        val methodBlocks = methods.map { method ->
            Block(
                id = "method:${method.name}",
                kind = BlockKind.METHOD,
                title = methodTitle(method),
                filePath = path,
                range = rangeWithDoc(method),
            )
        }

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        blocks += Block(
            id = HEADER_ID,
            kind = BlockKind.HEADER,
            title = headerTitle(file),
            filePath = path,
            range = SourceRange(0, classRange.start),
            collapsed = true,
        )
        blocks += Block(
            id = classId,
            kind = BlockKind.CLASS,
            title = classTitle(phpClass),
            filePath = path,
            range = classRange,
            excluded = methodBlocks.map { it.range },
        )
        blocks += methodBlocks
        links += methodBlocks.map { Link(LinkKind.OWNS, classId, it.id) }

        return BuildResult.Supported(BlockModel(blocks, links))
    }

    internal fun rangeWithDoc(element: PhpNamedElement): SourceRange {
        val start = minOf(element.textRange.startOffset, element.docComment?.textRange?.startOffset ?: Int.MAX_VALUE)
        return SourceRange(start, element.textRange.endOffset)
    }

    internal fun keyword(phpClass: PhpClass): String = when {
        phpClass.isInterface -> "interface"
        phpClass.isTrait -> "trait"
        phpClass.isEnum -> "enum"
        else -> "class"
    }

    private fun classTitle(phpClass: PhpClass): String = buildString {
        append(keyword(phpClass)).append(' ').append(phpClass.name)
        val extends = phpClass.extendsList.referenceElements.mapNotNull { it.name }
        if (extends.isNotEmpty()) append(" extends ").append(extends.joinToString(", "))
        val implements = phpClass.implementsList.referenceElements.mapNotNull { it.name }
        if (implements.isNotEmpty()) append(" implements ").append(implements.joinToString(", "))
    }

    private fun methodTitle(method: Method): String =
        method.name + method.parameters.joinToString(", ", "(", ")") { it.text.substringBefore('=').trim() }

    private fun headerTitle(file: PhpFile): String {
        val namespace = PsiTreeUtil.findChildOfType(file, PhpNamespace::class.java)?.fqn?.trimStart('\\')
        val imports = PsiTreeUtil.findChildrenOfType(file, PhpUse::class.java).count { !it.isTraitImport }
        val where = if (namespace.isNullOrEmpty()) "global namespace" else "namespace $namespace"
        return "$where · $imports imports"
    }
}
```

Note: the trailing-code check message uses `keyword(phpClass)`, so for the class in `testCodeAfterClassIsUnsupported` it reads "after the class".

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.PhpBlockBuilderStructureTest"`
Expected: PASS (11 tests). If a PSI accessor doesn't compile or PSI behaves differently than assumed (e.g. the doc comment is not reachable via `docComment`), see the PSI API note at the top; adjust the implementation, never the tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/readcodelikeahuman/php src/test/kotlin/com/readcodelikeahuman/php
git commit -m "feat: build header, class and method blocks from PHP PSI"
```

---

### Task 6: PHP Block Builder — interfaces, parent class and traits

**Files:**
- Modify: `src/main/kotlin/com/readcodelikeahuman/php/PhpBlockBuilder.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderRelatedTypesTest.kt`

**Interfaces:**
- Consumes: Task 5 `PhpBlockBuilder` (`rangeWithDoc`, `keyword`), `PhpBuilderTestCase` helpers.
- Produces:
  - Block ids `"interface:<FQN>"`, `"parent:<FQN>"`, `"trait:<FQN>"`; title `"<keyword> <Name>"`; `filePath` = the resolved type's file; `collapsed = true`.
  - Links into the class block: implemented interface → `IMPLEMENTS`; class's parent → `EXTENDS` (block kind PARENT); interface's parent interfaces → `EXTENDS` (block kind INTERFACE); trait → `USES`.
  - Resolution only via `ClassReference.resolve()` / `PhpClass.traits`; unresolved ⇒ no block.
  - Private helper `externalBlock(kind: BlockKind, target: PhpClass): Block` (reused in Task 7).

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderRelatedTypesTest.kt`:
```kotlin
package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderRelatedTypesTest : PhpBuilderTestCase() {
    fun testInterfacesParentAndTraitsBecomeCollapsedBlocks() {
        addPhp("src/Barro.php", "<?php\nnamespace App;\ninterface Barro { public function bar(): void; }\n")
        addPhp("src/Base.php", "<?php\nnamespace App;\nabstract class Base {}\n")
        addPhp("src/Loggable.php", "<?php\nnamespace App;\ntrait Loggable {}\n")

        val model = supported(
            """
            <?php
            namespace App;

            class Foo extends Base implements Barro
            {
                use Loggable;

                public function bar(): void {}
            }
            """.trimIndent() + "\n",
        )
        val cls = "class:\\App\\Foo"

        val iface = model.block("interface:\\App\\Barro")
        assertEquals(BlockKind.INTERFACE, iface.kind)
        assertEquals("interface Barro", iface.title)
        assertTrue(iface.collapsed)
        assertTrue(iface.filePath.endsWith("src/Barro.php"))

        assertEquals("class Base", model.block("parent:\\App\\Base").title)
        assertEquals("trait Loggable", model.block("trait:\\App\\Loggable").title)

        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTS, "interface:\\App\\Barro", cls)))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:\\App\\Base", cls)))
        assertTrue(model.links.contains(Link(LinkKind.USES, "trait:\\App\\Loggable", cls)))
    }

    fun testInterfaceExtendingInterfaces() {
        addPhp("src/Countable2.php", "<?php\nnamespace App;\ninterface Countable2 {}\n")
        val model = supported("<?php\nnamespace App;\ninterface Barro extends Countable2 {}\n")
        val parent = model.block("interface:\\App\\Countable2")
        assertEquals(BlockKind.INTERFACE, parent.kind)
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, parent.id, "class:\\App\\Barro")))
    }

    fun testUnresolvedInterfaceGetsNoBlock() {
        val model = supported("<?php\nnamespace App;\nclass Foo implements DoesNotExist {}\n")
        assertTrue(model.ofKind(BlockKind.INTERFACE).isEmpty())
        assertEquals("class Foo implements DoesNotExist", model.ofKind(BlockKind.CLASS).single().title)
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.PhpBlockBuilderRelatedTypesTest"`
Expected: FAIL — `NoSuchElementException` for `interface:\App\Barro` (blocks not built yet); `testUnresolvedInterfaceGetsNoBlock` passes already.

- [ ] **Step 3: Implement**

In `PhpBlockBuilder.kt`, add the imports:
```kotlin
import com.jetbrains.php.lang.psi.elements.ClassReference
```

Add these private functions to `PhpBlockBuilder`:
```kotlin
    private fun externalBlock(kind: BlockKind, target: PhpClass): Block = Block(
        id = "${kind.name.lowercase()}:${target.fqn}",
        kind = kind,
        title = "${keyword(target)} ${target.name}",
        filePath = target.containingFile.virtualFile.path,
        range = rangeWithDoc(target),
        collapsed = true,
    )

    private fun resolved(references: List<ClassReference>): List<PhpClass> =
        references.mapNotNull { it.resolve() as? PhpClass }.distinctBy { it.fqn }

    /** Related types in left-column order, each with the link kind pointing into the class block. */
    private fun relatedTypes(phpClass: PhpClass): List<Pair<Block, LinkKind>> {
        val parentKind = if (phpClass.isInterface) BlockKind.INTERFACE else BlockKind.PARENT
        return resolved(phpClass.implementsList.referenceElements).map { externalBlock(BlockKind.INTERFACE, it) to LinkKind.IMPLEMENTS } +
            resolved(phpClass.extendsList.referenceElements).map { externalBlock(parentKind, it) to LinkKind.EXTENDS } +
            phpClass.traits.distinctBy { it.fqn }.map { externalBlock(BlockKind.TRAIT, it) to LinkKind.USES }
    }
```

In `build`, after `links += methodBlocks.map { ... }`, add:
```kotlin
        relatedTypes(phpClass).forEach { (block, linkKind) ->
            blocks += block
            links += Link(linkKind, block.id, classId)
        }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.*"`
Expected: PASS (Task 5 + Task 6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/readcodelikeahuman/php src/test/kotlin/com/readcodelikeahuman/php
git commit -m "feat: add interface, parent and trait blocks via PSI resolution"
```

---

### Task 7: PHP Block Builder — constructor-injected dependencies

**Files:**
- Modify: `src/main/kotlin/com/readcodelikeahuman/php/PhpBlockBuilder.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderDependencyTest.kt`

**Interfaces:**
- Consumes: Task 6 `externalBlock(kind, target)`.
- Produces: blocks `"dependency:<FQN>"` (kind DEPENDENCY, collapsed) for every class/interface named in a **type declaration** of an own `__construct` parameter that resolves via PSI; link `INJECTS` dependency → class. Class references in default values (`Foo::BAR`) are ignored. Duplicates are merged.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderDependencyTest.kt`:
```kotlin
package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderDependencyTest : PhpBuilderTestCase() {
    fun testConstructorTypedParametersBecomeDependencies() {
        addPhp("src/UserRepo.php", "<?php\nnamespace App;\nclass UserRepo { const LIMIT = 10; }\n")
        addPhp("src/Clock.php", "<?php\nnamespace App;\ninterface Clock {}\n")

        val model = supported(
            """
            <?php
            namespace App;

            class Foo
            {
                public function __construct(
                    private UserRepo ${'$'}repo,
                    private ?Clock ${'$'}clock,
                    private UserRepo ${'$'}sameRepoAgain,
                    private string ${'$'}name,
                    private int ${'$'}limit = UserRepo::LIMIT,
                ) {}
            }
            """.trimIndent() + "\n",
        )

        val deps = model.ofKind(BlockKind.DEPENDENCY)
        assertEquals(listOf("dependency:\\App\\UserRepo", "dependency:\\App\\Clock"), deps.map { it.id })
        assertEquals(listOf("class UserRepo", "interface Clock"), deps.map { it.title })
        assertTrue(deps.all { it.collapsed })
        assertTrue(model.links.contains(Link(LinkKind.INJECTS, "dependency:\\App\\UserRepo", "class:\\App\\Foo")))
    }

    fun testUnresolvedDependencyGetsNoBlock() {
        val model = supported("<?php\nclass Foo { public function __construct(private Missing ${'$'}m) {} }\n")
        assertTrue(model.ofKind(BlockKind.DEPENDENCY).isEmpty())
    }

    fun testNoConstructorMeansNoDependencies() {
        val model = supported("<?php\nclass Foo { public function bar() {} }\n")
        assertTrue(model.ofKind(BlockKind.DEPENDENCY).isEmpty())
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.PhpBlockBuilderDependencyTest"`
Expected: FAIL in `testConstructorTypedParametersBecomeDependencies` (no dependency blocks yet).

- [ ] **Step 3: Implement**

In `PhpBlockBuilder.kt`, add the import:
```kotlin
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration
```

Add to `PhpBlockBuilder`:
```kotlin
    private fun dependencies(phpClass: PhpClass): List<Block> {
        val constructor = phpClass.ownMethods.firstOrNull { it.name.equals("__construct", ignoreCase = true) }
            ?: return emptyList()
        val typeReferences = constructor.parameters.flatMap { parameter ->
            PsiTreeUtil.findChildrenOfType(parameter, ClassReference::class.java)
                .filter { PsiTreeUtil.getParentOfType(it, PhpTypeDeclaration::class.java) != null }
        }
        return resolved(typeReferences).map { externalBlock(BlockKind.DEPENDENCY, it) }
    }
```

In `build`, after the `relatedTypes(...)` loop, add:
```kotlin
        dependencies(phpClass).forEach { block ->
            blocks += block
            links += Link(LinkKind.INJECTS, block.id, classId)
        }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/readcodelikeahuman/php src/test/kotlin/com/readcodelikeahuman/php
git commit -m "feat: add constructor-injected dependency blocks"
```

---

### Task 8: PHP Block Builder — method-to-method calls

**Files:**
- Modify: `src/main/kotlin/com/readcodelikeahuman/php/PhpBlockBuilder.kt`
- Test: `src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderCallsTest.kt`

**Interfaces:**
- Consumes: Task 5 method blocks (ids `method:<name>`), the `methods` list inside `build`.
- Produces: `CALLS` links `method:<caller>` → `method:<callee>` for every `MethodReference` inside a method block that resolves (via PSI) to another method block of the same class. No self-links, no duplicates, nothing for calls on other objects, trait methods or the constructor.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/readcodelikeahuman/php/PhpBlockBuilderCallsTest.kt`:
```kotlin
package com.readcodelikeahuman.php

import com.readcodelikeahuman.model.LinkKind

class PhpBlockBuilderCallsTest : PhpBuilderTestCase() {
    fun testInternalCallsBecomeLinks() {
        val model = supported(
            """
            <?php
            class Foo
            {
                public function bar(): void
                {
                    ${'$'}this->footest('x');
                    ${'$'}this->footest('y');
                    self::helper();
                    static::helper();
                }

                public function footest(string ${'$'}s): void {}

                private static function helper(): void {}
            }
            """.trimIndent() + "\n",
        )
        val calls = model.links.filter { it.kind == LinkKind.CALLS }.map { it.from to it.to }
        assertEquals(listOf("method:bar" to "method:footest", "method:bar" to "method:helper"), calls)
    }

    fun testCallsIgnoreOtherObjectsAndRecursion() {
        addPhp("src/Other.php", "<?php\nclass Other { public function bar(): void {} }\n")
        addPhp("src/Helps.php", "<?php\ntrait Helps { public function assist(): void {} }\n")
        val model = supported(
            """
            <?php
            class Foo
            {
                use Helps;

                public function __construct(private Other ${'$'}other)
                {
                    ${'$'}this->bar();
                }

                public function bar(): void
                {
                    ${'$'}this->other->bar();
                    ${'$'}this->bar();
                    ${'$'}this->assist();
                }
            }
            """.trimIndent() + "\n",
        )
        assertTrue(model.links.none { it.kind == LinkKind.CALLS })
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew test --tests "com.readcodelikeahuman.php.PhpBlockBuilderCallsTest"`
Expected: FAIL in `testInternalCallsBecomeLinks` (no CALLS links yet).

- [ ] **Step 3: Implement**

In `PhpBlockBuilder.kt`, add the import:
```kotlin
import com.jetbrains.php.lang.psi.elements.MethodReference
```

Add to `PhpBlockBuilder`:
```kotlin
    private fun calls(phpClass: PhpClass, methods: List<Method>): List<Link> =
        methods.flatMap { caller ->
            PsiTreeUtil.findChildrenOfType(caller, MethodReference::class.java)
                .mapNotNull { it.resolve() as? Method }
                .filter { callee -> callee != caller && callee in methods && callee.containingClass == phpClass }
                .distinct()
                .map { callee -> Link(LinkKind.CALLS, "method:${caller.name}", "method:${callee.name}") }
        }
```

In `build`, after the `dependencies(...)` loop, add:
```kotlin
        links += calls(phpClass, methods)
```

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew test`
Expected: PASS — all tests in `SmokeTest`, `BlockModelTest`, `LayoutEngineTest`, and the four `PhpBlockBuilder*Test` classes.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/readcodelikeahuman/php src/test/kotlin/com/readcodelikeahuman/php
git commit -m "feat: add method-to-method call links"
```

---

## After this plan

Write the **Canvas Editor plan** using the spike findings (`docs/superpowers/spikes/2026-10-08-slice-editor-findings.md`). It covers the remaining spec items, which are intentionally not in this plan: the `FileEditorProvider` that replaces the text editor and the "blocks by default" setting, the open-as-blocks command, the classic-view toggle and the unsupported-file notice, pan/zoom canvas rendering of `Layout`, slice editors per block, expand/collapse of other-file blocks, usage-highlight borders, `+ method` and pop-out on re-parse, delete with confirmation, keyboard/focus navigation, go-to-declaration focusing blocks, and keeping the last good layout while the PSI is invalid.
