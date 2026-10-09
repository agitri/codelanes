package dev.codelanes.canvas

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.codelanes.layout.Point
import dev.codelanes.settings.PinStore

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

    fun testRenamingAMethodKeepsItsEditorAndPin() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        val editor = session.sliceEditor("method:bar")
        edit("function bar()", "function baz()")
        session.rebuildNow()
        val pins = PinStore.getInstance(project).pins(myFixture.file.virtualFile.path)
        assertFalse(pins.containsKey("method:bar"))
        assertEquals(Point(900, 900), pins["method:baz"])
        assertFalse(session.blockIds().contains("method:bar"))
        assertSame(editor, session.sliceEditor("method:baz"))
    }

    private fun visible(slice: dev.codelanes.editor.SliceEditor): String {
        val text = slice.editor.document.charsSequence
        val folds = slice.editor.foldingModel.allFoldRegions.filter { it.isValid && !it.isExpanded }
        return text.indices.filter { o -> folds.none { o >= it.startOffset && o < it.endOffset } }.map { text[it] }.joinToString("")
    }

    fun testZoomWhileCodeIsBrokenKeepsSlicesOnTheirOwnCode() {
        val session = open()
        edit("    public function footest(string ${'$'}s): string\n    {\n        return ${'$'}s;\n    }\n", "    public function (\n")
        session.rebuildNow()
        session.canvas.setZoom(1.2)
        assertTrue(visible(session.sliceEditor("method:bar")!!).startsWith("public function bar(): string"))
    }

    fun testCollapsingWhileCodeIsBrokenKeepsSlicesOnTheirOwnCode() {
        val session = open()
        edit("private string ${'$'}name = 'x';\n", "private string ${'$'}name = 'x';\n    private int ${'$'}extraField = 1234567890;\n    public function (\n")
        session.rebuildNow()
        session.collapseToggled("method:footest")
        assertTrue(visible(session.sliceEditor("method:bar")!!).startsWith("public function bar(): string"))
    }

    fun testEditingAnExpandedInterfaceBlockKeepsItCorrect() {
        val barro = myFixture.addFileToProject("Barro.php", "<?php\nnamespace App;\n\ninterface Barro\n{\n    public function bar(): string;\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("class Foo", "class Foo implements Barro"))
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        session.collapseToggled("interface:\\App\\Barro")
        val slice = session.sliceEditor("interface:\\App\\Barro")!!
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = slice.editor.document
            doc.insertString(doc.text.indexOf("interface Barro"), "/** Added by hand. */\n")
        }
        session.canvas.setZoom(1.1)
        assertTrue(visible(slice).startsWith("interface Barro"))
    }

    fun testEditInsideAMethodDoesNotRefoldOtherSlices() {
        val session = open()
        val classSlice = session.sliceEditor("class:\\App\\Foo")!!
        val folds = classSlice.editor.foldingModel.allFoldRegions.toList()
        edit("return ${'$'}s;", "return ${'$'}s . 'x';")
        session.rebuildNow()
        assertEquals(folds, classSlice.editor.foldingModel.allFoldRegions.toList())
    }

    fun testTallMethodScrollsInsideItsBlock() {
        val body = (1..80).joinToString("") { "        echo $it;\n" }
        val psi = myFixture.configureByText("Tall.php", "<?php\nclass Tall\n{\n    public function long(): void\n    {\n$body    }\n\n    public function short(): void\n    {\n    }\n}\n")
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        assertTrue(session.sliceEditor("method:long")!!.scrollable)
        assertFalse(session.sliceEditor("method:short")!!.scrollable)
    }

    fun testCollapseToggleReleasesAndRecreatesTheEditor() {
        val session = open()
        session.collapseToggled("method:bar")
        assertFalse(session.hasSliceEditor("method:bar"))
        session.collapseToggled("method:bar")
        assertTrue(session.hasSliceEditor("method:bar"))
    }

    fun testScrollingOverABlocksCodePansTheCanvas() {
        val session = open()
        val before = session.viewBounds("method:bar")!!.location
        val content = session.sliceEditor("method:bar")!!.editor.contentComponent
        content.dispatchEvent(
            java.awt.event.MouseWheelEvent(content, java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 5, 5, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1),
        )
        assertEquals(java.awt.Point(before.x, before.y - 40), session.viewBounds("method:bar")!!.location)
    }

    fun testResetLayoutDropsPinsAndRestoresAutoPositions() {
        val session = open()
        val auto = session.viewBounds("method:bar")!!.location
        session.blockMoved("method:bar", Point(900, 900))
        session.resetLayout()
        assertTrue(PinStore.getInstance(project).pins(myFixture.file.virtualFile.path).isEmpty())
        assertEquals(auto, session.viewBounds("method:bar")!!.location)
    }

    fun testBackgroundRebuildPicksUpANewMethod() {
        val session = open()
        edit("        return ${'$'}s;\n    }\n", "        return ${'$'}s;\n    }\n\n    public function added(): void {}\n")
        val deadline = System.currentTimeMillis() + 10_000
        while (!session.blockIds().contains("method:added") && System.currentTimeMillis() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            Thread.sleep(20)
        }
        assertTrue(session.blockIds().contains("method:added"))
    }

    fun testMethodTypedInTheFocusedClassBlockPopsOutWhenFocusLeaves() {
        val session = open()
        session.focusMovedTo("class:\\App\\Foo")
        edit("private string ${'$'}name = 'x';\n", "private string ${'$'}name = 'x';\n\n    public function added(): void {}\n")
        session.rebuildNow()
        assertFalse(session.blockIds().contains("method:added"))
        session.focusMovedTo(null)
        assertTrue(session.blockIds().contains("method:added"))
    }

    fun testTidyUpResetsTheLayout() {
        val session = open()
        session.blockMoved("method:bar", Point(900, 900))
        session.tidyUp()
        assertTrue(PinStore.getInstance(project).pins(myFixture.file.virtualFile.path).isEmpty())
    }

    fun testScrollingOverABlocksGutterPansTheCanvasToo() {
        val session = open()
        val before = session.viewBounds("method:bar")!!.location
        val gutter = session.sliceEditor("method:bar")!!.editor.gutterComponentEx
        gutter.dispatchEvent(
            java.awt.event.MouseWheelEvent(gutter, java.awt.event.MouseEvent.MOUSE_WHEEL, 0L, 0, 2, 5, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1),
        )
        assertEquals(java.awt.Point(before.x, before.y - 40), session.viewBounds("method:bar")!!.location)
    }

    fun testCaretInsideAnExpandedParentMethodShowsItsOverrideLine() {
        myFixture.addFileToProject("Base.php", "<?php\nnamespace App;\n\nabstract class Base\n{\n    protected int ${'$'}x = 0;\n\n    abstract public function bar(): string;\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("class Foo", "class Foo extends Base"))
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        val parentId = "parent:\\App\\Base"
        session.collapseToggled(parentId)
        val slice = session.sliceEditor(parentId)!!
        session.focusMovedTo(parentId)
        slice.editor.caretModel.moveToOffset(slice.editor.document.text.indexOf("x = 0"))
        assertTrue(session.canvas.visibleLinks().none { it.kind == dev.codelanes.model.LinkKind.OVERRIDES })
        slice.editor.caretModel.moveToOffset(slice.editor.document.text.indexOf("function bar"))
        assertEquals(listOf("method:bar"), session.canvas.visibleLinks().filter { it.kind == dev.codelanes.model.LinkKind.OVERRIDES }.map { it.from })
    }

    fun testTheMoreBlockNeverOpensAnEditor() {
        (1..11).forEach { n -> myFixture.addFileToProject("R$n.php", "<?php\nnamespace App;\nclass R$n implements Renderable { public function render(): string { return ''; } }\n") }
        val psi = myFixture.configureByText("Renderable.php", "<?php\nnamespace App;\n\ninterface Renderable\n{\n    public function render(): string;\n}\n")
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        assertTrue(session.blockIds().contains("more:implementers"))
        session.collapseToggled("more:implementers")
        assertFalse(session.hasSliceEditor("more:implementers"))
    }

    fun testRevealFocusesTheBlockAtAnOffsetAndPutsTheCaretThere() {
        val session = open()
        val offset = myFixture.editor.document.text.indexOf("footest(string")
        session.reveal(offset)
        assertEquals("method:footest", session.canvas.focusedId)
        assertEquals(offset, session.sliceEditor("method:footest")!!.editor.caretModel.offset)
    }

    fun testRevealExpandsACollapsedBlock() {
        val session = open()
        session.collapseToggled("method:footest")
        session.reveal(myFixture.editor.document.text.indexOf("footest(string"))
        assertTrue(session.hasSliceEditor("method:footest"))
    }

    fun testRevealCentresTheBlockOnTheCanvas() {
        val session = open()
        session.canvas.setSize(1000, 800)
        session.reveal(myFixture.editor.document.text.indexOf("footest(string"))
        val bounds = session.viewBounds("method:footest")!!
        assertEquals(500, bounds.centerX.toInt())
        assertEquals(400, bounds.centerY.toInt())
    }

    fun testCaretOnACallHighlightsTheDefinitionAndTheUsingBlocks() {
        val session = open()
        val text = myFixture.editor.document.text
        session.highlightUsagesAt("method:bar", text.indexOf("footest(${'$'}this"))
        assertEquals(UsageHighlight.Level.DEFINITION, session.canvas.highlights["method:footest"])
        assertEquals(UsageHighlight.Level.USAGE, session.canvas.highlights["method:bar"])
    }

    fun testCaretOnNothingClearsTheHighlights() {
        val session = open()
        val text = myFixture.editor.document.text
        session.highlightUsagesAt("method:bar", text.indexOf("footest(${'$'}this"))
        session.highlightUsagesAt("method:bar", text.indexOf("return ${'$'}this->footest") + 2)
        assertTrue(session.canvas.highlights.isEmpty())
    }

    fun testPlusMethodAddsAnEmptyMethodBlockWithItsNameSelected() {
        val session = open()
        session.addMethod()
        assertTrue(session.blockIds().contains("method:newMethod"))
        val editor = session.sliceEditor("method:newMethod")!!.editor
        assertEquals("newMethod", editor.selectionModel.selectedText)
        assertTrue(myFixture.editor.document.text.contains("    public function newMethod(): void\n    {\n    }\n}"))
    }

    fun testPlusMethodPicksAFreeName() {
        val session = open()
        session.addMethod()
        session.addMethod()
        assertTrue(session.blockIds().containsAll(listOf("method:newMethod", "method:newMethod2")))
    }

    fun testPlusMethodInAnInterfaceAddsASignature() {
        val psi = myFixture.configureByText("Renderable.php", "<?php\nnamespace App;\n\ninterface Renderable\n{\n    public function render(): string;\n}\n")
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        session.addMethod()
        assertTrue(myFixture.editor.document.text.contains("    public function newMethod(): void;\n}"))
        assertTrue(session.blockIds().contains("method:newMethod"))
    }

    fun testDeleteMethodRemovesItsLinesAndBlock() {
        val session = open()
        session.deleteMethod("method:footest") { true }
        assertFalse(session.blockIds().contains("method:footest"))
        val text = myFixture.editor.document.text
        assertFalse(text.contains("footest(string"))
        assertTrue(text.contains("        return ${'$'}this->footest(${'$'}this->name);\n    }\n}"))
    }

    fun testDeleteMethodCanBeCancelled() {
        val session = open()
        session.deleteMethod("method:footest") { false }
        assertTrue(session.blockIds().contains("method:footest"))
    }

    fun testClassOffersPlusMethodAndMethodsOfferDelete() {
        val session = open()
        assertEquals(listOf("+ method"), session.view("class:\\App\\Foo")!!.actionTexts())
        assertEquals("Delete method", session.view("method:bar")!!.menuTexts().first())
    }

    fun testMovingFocusDownEntersTheNextBlockAtItsStart() {
        val session = open()
        assertTrue(session.moveFocus("method:bar", down = true))
        assertEquals("method:footest", session.canvas.focusedId)
        val footest = session.sliceEditor("method:footest")!!.editor
        assertEquals(myFixture.editor.document.text.indexOf("public function footest"), footest.caretModel.offset)
    }

    fun testPlusParentsRevealsAndHidesTheNextLevel() {
        myFixture.addFileToProject("Root.php", "<?php\nnamespace App;\n\nabstract class Root\n{\n}\n")
        myFixture.addFileToProject("Base.php", "<?php\nnamespace App;\n\nabstract class Base extends Root\n{\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("class Foo", "class Foo extends Base"))
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        val base = "parent:\\App\\Base"
        assertEquals(listOf("+ parents"), session.view(base)!!.actionTexts())
        session.toggleReveal(base)
        assertTrue(session.blockIds().contains("parent:\\App\\Root"))
        assertEquals(listOf("− parents"), session.view(base)!!.actionTexts())
        session.toggleReveal(base)
        assertFalse(session.blockIds().contains("parent:\\App\\Root"))
    }

    fun testCollapsedSummaryOfAnotherFileRefreshesWhenThatFileChanges() {
        val barro = myFixture.addFileToProject("Barro.php", "<?php\nnamespace App;\n\ninterface Barro\n{\n    public function bar(): string;\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("class Foo", "class Foo implements Barro"))
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(barro)!!
            doc.insertString(doc.text.lastIndexOf("}"), "    public function extra(): void;\n")
        }
        session.rebuildNow()
        assertTrue(session.summaryText("interface:\\App\\Barro").contains("extra()"))
    }

    fun testTypingANewMethodAfterTheLastBraceKeepsTheCaretWhereYouType() {
        val session = open()
        val footest = session.sliceEditor("method:footest")!!.editor
        session.focusMovedTo("method:footest")
        val end = myFixture.editor.document.text.indexOf("        return ${'$'}s;\n    }") + "        return ${'$'}s;\n    }".length
        WriteCommandAction.runWriteCommandAction(project) {
            footest.caretModel.moveToOffset(end)
            com.intellij.openapi.editor.EditorModificationUtil.insertStringAtCaret(footest, "\n\n    public function added(): void {}")
        }
        val typedTo = footest.caretModel.offset
        session.rebuildNow()
        assertEquals(typedTo, footest.caretModel.offset)
        session.focusMovedTo(null)
        assertTrue(session.blockIds().contains("method:added"))
    }

    fun testPlusMethodStaysInsideTheClassEvenWithTextAfterItsBrace() {
        val session = open()
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = myFixture.editor.document
            doc.insertString(doc.text.lastIndexOf("}") + 1, "\n")
        }
        session.addMethod()
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("    public function newMethod(): void\n    {\n    }\n}"))
        assertTrue(session.blockIds().contains("method:newMethod"))
    }

    fun testPlusCallsFollowsAMethodIntoOtherClassesAndBack() {
        myFixture.addFileToProject("Repo.php", "<?php\nnamespace App;\n\nclass Repo\n{\n    public function find(): string\n    {\n        return 'x';\n    }\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("return ${'$'}s;", "return (new Repo())->find();"))
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        assertTrue(session.view("method:footest")!!.actionTexts().contains("+ calls"))
        session.toggleCalls("method:footest")
        assertTrue(session.blockIds().contains("callee:\\App\\Repo::find"))
        assertTrue(session.hasSliceEditor("callee:\\App\\Repo::find"))
        assertTrue(session.view("method:footest")!!.actionTexts().contains("− calls"))
        session.toggleCalls("method:footest")
        assertFalse(session.blockIds().contains("callee:\\App\\Repo::find"))
    }

    fun testReviewMarkShowsOnTheBlockAndBecomesChangedWhenTheCodeChanges() {
        val session = open()
        session.markBlock("method:footest", dev.codelanes.review.ReviewMark.UNDERSTOOD)
        assertEquals("✓", session.view("method:footest")!!.reviewBadge())
        edit("return ${'$'}s;", "return ${'$'}s . '!';")
        session.rebuildNow()
        assertEquals("~", session.view("method:footest")!!.reviewBadge())
    }

    fun testReviewNoteShowsUnderTheTitle() {
        val session = open()
        session.setNote("method:bar", "why does this call footest?")
        assertEquals("why does this call footest?", session.view("method:bar")!!.noteText())
    }

    fun testEveryBlockOffersTheReviewMenu() {
        val session = open()
        val menu = session.view("method:bar")!!.menuTexts()
        assertTrue(menu.containsAll(listOf("✓ Understood", "? Don't understand", "! Needs change", "Clear review mark", "Edit review note…")))
        assertTrue(session.view("class:\\App\\Foo")!!.menuTexts().contains("✓ Understood"))
    }

    fun testACanvasStateCanBeSavedAndRestoredInAnotherSession() {
        myFixture.addFileToProject("Repo.php", "<?php\nnamespace App;\n\nclass Repo\n{\n    public function find(): string\n    {\n        return 'x';\n    }\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("return ${'$'}s;", "return (new Repo())->find();"))
        val first = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        first.toggleCalls("method:footest")
        first.collapseToggled("method:bar")
        first.canvas.setZoom(0.8)
        val state = first.snapshot()

        val second = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        second.restore(state)
        assertTrue(second.blockIds().contains("callee:\\App\\Repo::find"))
        assertFalse(second.hasSliceEditor("method:bar"))
        assertEquals(0.8, second.canvas.zoom, 0.001)
        assertEquals(psi.virtualFile.path, state.file)
    }

    fun testAddingAnUnrelatedMethodKeepsTheClassReviewed() {
        val session = open()
        session.markBlock("class:\\App\\Foo", dev.codelanes.review.ReviewMark.UNDERSTOOD)
        edit("        return ${'$'}s;\n    }\n", "        return ${'$'}s;\n    }\n\n    public function added(): void {}\n")
        session.rebuildNow()
        assertEquals("✓", session.view("class:\\App\\Foo")!!.reviewBadge())
    }

    fun testFollowedCallsSurviveARename() {
        myFixture.addFileToProject("Repo.php", "<?php\nnamespace App;\n\nclass Repo\n{\n    public function find(): string\n    {\n        return 'x';\n    }\n}\n")
        val psi = myFixture.configureByText("Foo.php", source.replace("return ${'$'}s;", "return (new Repo())->find();"))
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        session.toggleCalls("method:footest")
        edit("function footest(", "function footester(")
        session.rebuildNow()
        assertTrue(session.blockIds().contains("callee:\\App\\Repo::find"))
    }

    fun testFollowingAChainGoesSeveralLevelsDeepAlsoThroughInterfaces() {
        myFixture.addFileToProject("Service.php", "<?php\nnamespace App;\n\nclass Service\n{\n    public function __construct(private Store ${'$'}store) {}\n\n    public function place(): void\n    {\n        ${'$'}this->store->save();\n    }\n}\n")
        myFixture.addFileToProject("Store.php", "<?php\nnamespace App;\n\ninterface Store\n{\n    public function save(): void;\n}\n")
        myFixture.addFileToProject("DbStore.php", "<?php\nnamespace App;\n\nclass DbStore implements Store\n{\n    public function save(): void\n    {\n        ${'$'}this->flush();\n    }\n\n    private function flush(): void\n    {\n    }\n}\n")
        val controller = "<?php\nnamespace App;\n\nclass Controller\n{\n    public function __construct(private Service ${'$'}service) {}\n\n    public function create(): void\n    {\n        ${'$'}this->service->place();\n    }\n}\n"
        val psi = myFixture.configureByText("Controller.php", controller)
        val session = BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
        session.followChain("method:create", depth = 4)
        assertTrue(session.blockIds().containsAll(listOf(
            "callee:\\App\\Service::place",
            "callee:\\App\\Store::save",
            "callee:\\App\\DbStore::save",
            "callee:\\App\\DbStore::flush",
        )))
        assertEquals("method:create", session.canvas.focusedId)
    }

    fun testTheVerticalLayoutSwitchStacksEveryBlock() {
        val session = open()
        val settings = dev.codelanes.settings.BlocksSettings.instance.state
        try {
            settings.verticalLayout = true
            session.refreshLayout()
            assertEquals(1, session.blockIds().map { session.viewBounds(it)!!.x }.distinct().size)
        } finally {
            settings.verticalLayout = false
        }
    }
}
