package dev.codelanes.canvas

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.codelanes.editor.SliceEditor
import dev.codelanes.layout.LayoutEngine
import dev.codelanes.layout.Point
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange
import dev.codelanes.notes.NotesFile
import dev.codelanes.php.PhpBlockBuilder
import dev.codelanes.review.Review
import dev.codelanes.review.ReviewEntry
import dev.codelanes.review.ReviewFile
import dev.codelanes.review.ReviewMark
import dev.codelanes.workingset.CanvasState
import dev.codelanes.settings.PinStore
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Keeps the canvas in sync with one PHP file.
 *
 * A fresh build ([adopt]) is the only thing that decides *which* blocks exist and where they start;
 * between builds every block's range is tracked with range markers, so re-rendering (zoom, collapse)
 * never folds a slice with stale offsets.
 */
class BlocksSession(private val project: Project, private val file: VirtualFile) : Disposable, BlocksCanvas.Listener {
    val canvas = BlocksCanvas(this)
    var model: BlockModel? = null
        private set

    private val document = FileDocumentManager.getInstance().getDocument(file) ?: error("No document for $file")
    private val views = linkedMapOf<String, BlockView>()
    private val slices = mutableMapOf<String, SliceEditor>()
    private val collapsed = mutableMapOf<String, Boolean>()
    private val tracked = mutableMapOf<String, TrackedBlock>()
    private val summaries = mutableMapOf<String, Pair<List<String>, JComponent>>()
    private val watchedDocuments = mutableSetOf<Document>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val highlightAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val baseFontSize = EditorColorsManager.getInstance().globalScheme.editorFontSize
    private var disposed = false
    private var focused: String? = null
    private var heldBack = false
    private var rebuildAfterRename = false
    private val revealed = mutableSetOf<String>()
    private val notesFile = NotesFile.getInstance(project)
    private val noteAreas = mutableMapOf<String, com.intellij.ui.components.JBTextArea>()
    private val noteAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val reviews = ReviewFile.getInstance(project)
    private val followedCalls = mutableSetOf<String>()
    private val added = mutableSetOf<String>()

    init {
        canvas.targetLineY = ::targetLineY
        val settings = dev.codelanes.settings.BlocksSettings.instance
        canvas.setLayoutMode(settings.mode)
        settings.addModeListener(this) {
            canvas.setLayoutMode(settings.mode)
            refreshLayout()
        }
        watch(document)
        // Review marks changed on disk (git pull, a teammate, another canvas): refresh the badges.
        project.messageBus.connect(this).subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
            override fun after(events: List<com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                if (events.any { it.path.endsWith("/" + ReviewFile.PATH) || it.path.endsWith("/" + NotesFile.PATH) }) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ if (!disposed) withModelAccess { render() } }, { disposed })
                }
            }
        })
        try {
            rebuildNow()
        } catch (e: Throwable) {
            // Not yet registered with a parent disposable: release editors and listeners ourselves.
            Disposer.dispose(this)
            throw e
        }
    }

    private fun watch(doc: Document) {
        if (!watchedDocuments.add(doc)) return
        doc.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleRebuild()
        }, this)
    }

    private fun scheduleRebuild() {
        if (disposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ rebuildInBackground() }, REBUILD_DELAY_MS)
    }

    /** Completion popups and live templates (e.g. in-place rename) must not lose their editor mid-edit. */
    private fun busyEditing(): Boolean = slices.values.any {
        LookupManager.getActiveLookup(it.editor) != null || TemplateManager.getInstance(project).getActiveTemplate(it.editor) != null
    }

    private fun rebuildInBackground() {
        if (disposed) return
        if (busyEditing()) {
            scheduleRebuild()
            return
        }
        val previous = model
        val reveal = revealed.toSet()
        val follow = followedCalls.toSet()
        val extra = added.toSet()
        ReadAction.nonBlocking<CanvasUpdate?> { computeUpdate(previous, reveal, follow, extra) }
            .withDocumentsCommitted(project)
            .inSmartMode(project)
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { update -> update?.let(::applyUpdate) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Synchronous rebuild: used on open and by tests. Falls back to the background rebuild when it can't commit here. */
    fun rebuildNow() {
        if (disposed) return
        val documents = PsiDocumentManager.getInstance(project)
        // Blocks can show other files (an expanded interface), so every pending edit must reach the parser.
        if (documents.hasUncommitedDocuments()) {
            // Committing is a model change; e.g. inside FileEditorProvider.createEditor it isn't allowed.
            if (!TransactionGuard.getInstance().isWritingAllowed) {
                scheduleRebuild()
                return
            }
            documents.commitAllDocuments()
        }
        if (DumbService.isDumb(project)) {
            scheduleRebuild()
            return
        }
        ReadAction.compute<CanvasUpdate?, RuntimeException> { computeUpdate(model, revealed.toSet(), followedCalls.toSet(), added.toSet()) }?.let(::applyUpdate)
    }

    private fun computeUpdate(previous: BlockModel?, reveal: Set<String>, follow: Set<String>, extra: Set<String>): CanvasUpdate? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return RebuildPolicy.next(previous, PhpBlockBuilder.build(psi, reveal, follow, extra), PsiTreeUtil.hasErrorElements(psi))
    }

    private fun applyUpdate(update: CanvasUpdate) {
        if (disposed) return
        canvas.notice = update.notice
        val next = update.model ?: return
        if (next === model) return
        if (wouldPullTextOutOfFocusedBlock(next)) {
            heldBack = true
            canvas.notice = "New block appears when you leave this one"
            return
        }
        withModelAccess { adopt(next) }
        if (rebuildAfterRename) {
            rebuildAfterRename = false
            rebuildNow()
        }
    }

    /**
     * While someone types in a block, don't move what they're typing into a new block under their caret: hold the
     * build back if the caret would end up outside the focused block (e.g. a method typed inside the class block,
     * or after the last brace of a method block).
     */
    private fun wouldPullTextOutOfFocusedBlock(next: BlockModel): Boolean {
        val id = focused ?: return false
        val after = next.blocks.firstOrNull { it.id == id } ?: return false
        val lostTextToANewBlock = (tracked[id]?.current()?.second?.size ?: 0) < after.excluded.size
        val caret = slices[id]?.editor?.caretModel?.offset ?: return lostTextToANewBlock
        val inside = caret >= after.range.start && caret <= after.range.end &&
            after.excluded.none { caret > it.start && caret < it.end }
        return lostTextToANewBlock || !inside
    }

    /** UI events arrive on the EDT without a read lock; documents, editors and folds need one. */
    private fun withModelAccess(action: () -> Unit) = WriteIntentReadAction.run(Runnable(action))

    /** Takes over a freshly built model: decides which blocks exist and re-tracks their ranges. */
    private fun adopt(next: BlockModel) {
        val live = next.blocks.map { it.id }.toSet() + notesFile.notes(file.path).map { NOTE_PREFIX + it.id }
        carryOverRename(next, views.keys - live, live - views.keys)
        (views.keys - live).toList().forEach(::forget)
        model = next
        for (block in next.blocks) {
            tracked.remove(block.id)?.dispose()
            val doc = documentOf(block) ?: continue
            watch(doc)
            tracked[block.id] = TrackedBlock(doc, block)
        }
        PinStore.getInstance(project).prune(file.path, live)
        render()
    }

    /** One method gone and one method new in the same build is a rename: keep its view, editor, state and pin. */
    private fun carryOverRename(next: BlockModel, vanished: Set<String>, appeared: Set<String>) {
        // Only method blocks count: blocks hanging off a method (e.g. its followed callees) may vanish with it.
        val oldMethods = model?.blocks.orEmpty().filter { it.kind == BlockKind.METHOD }.map { it.id }.toSet()
        val old = vanished.filter { it in oldMethods }.singleOrNull() ?: return
        val new = appeared.filter { next.block(it).kind == BlockKind.METHOD }.singleOrNull() ?: return
        views.remove(old)?.let { it.id = new; views[new] = it }
        slices.remove(old)?.let { slices[new] = it }
        collapsed.remove(old)?.let { collapsed[new] = it }
        summaries.remove(old)
        tracked.remove(old)?.dispose()
        val pins = PinStore.getInstance(project)
        pins.pins(file.path)[old]?.let { pins.pin(file.path, new, it) }
        reviews.move(file.path, old, new)
        if (followedCalls.remove(old)) {
            followedCalls += new
            rebuildAfterRename = true // this build still used the old name for its followed calls
        }
        if (focused == old) {
            focused = new
            canvas.focusedId = new
        }
    }

    private fun forget(id: String) {
        views.remove(id)
        slices.remove(id)?.let(Disposer::dispose)
        collapsed.remove(id)
        tracked.remove(id)?.dispose()
        summaries.remove(id)
    }

    /** Re-renders the current blocks (after a build, zoom or collapse) using their tracked ranges. */
    private fun render() {
        val current = model ?: return
        for (block in current.blocks) {
            val isCollapsed = collapsed.getOrPut(block.id) { block.collapsed }
            val view = views.getOrPut(block.id) { BlockView(block.id, canvas) }
            val slice = if (isCollapsed || block.kind == BlockKind.MORE) null else sliceFor(block)
            if (slice == null) {
                slices.remove(block.id)?.let(Disposer::dispose)
            } else {
                tracked[block.id]?.current()?.let { (range, excluded) -> slice.show(range, excluded) }
            }
            view.update(block, slice == null, slice?.component ?: summaryOf(block), canvas.zoom)
            actionsFor(block).let { (actions, menu) ->
                view.setActions(actions, if (block.kind == BlockKind.MORE) menu else menu + reviewMenu(block))
            }
            val review = reviews.get(block.filePath, block.id)
            val status = review?.mark?.let { Review.status(review, codeHash(block)) }
            view.setReview(status, review?.note.orEmpty())
        }
        renderNotes()
        canvas.setContent(LinkedHashMap(views), current.links + noteLinks())
        relayout()
        slices.forEach { (id, slice) -> slice.setScrollable(views[id]?.overflows(canvas.zoom) == true) }
    }

    private fun relayout() {
        val current = model ?: return
        val pins = PinStore.getInstance(project).pins(file.path)
        val mode = dev.codelanes.settings.BlocksSettings.instance.mode
        canvas.tree = mode == dev.codelanes.layout.LayoutMode.TREE
        val layout = LayoutEngine.layout(current, { views.getValue(it.id).naturalSize(canvas.zoom) }, pins, mode)
        canvas.place(layout.rects + noteRects(layout.rects))
    }

    private fun fileOf(block: Block): VirtualFile? =
        if (block.filePath == file.path) file else file.fileSystem.findFileByPath(block.filePath)

    private fun documentOf(block: Block): Document? = fileOf(block)?.let { FileDocumentManager.getInstance().getDocument(it) }

    private fun sliceFor(block: Block): SliceEditor? {
        slices[block.id]?.let { return it }
        val target = fileOf(block) ?: return null
        val targetDocument = FileDocumentManager.getInstance().getDocument(target) ?: return null
        val slice = SliceEditor(project, target, targetDocument)
        Disposer.register(this, slice)
        slice.setFontSize(fontSize())
        slice.onEdge = { up -> slices.entries.firstOrNull { it.value === slice }?.key?.let { moveFocus(it, down = !up) } ?: false }
        // The wheel anywhere over a block (code, gutter, frame) pans/zooms the canvas, unless the block itself
        // needs to scroll. Every part needs it: blocks slide under the pointer while panning.
        listOf(slice.editor.contentComponent, slice.editor.gutterComponentEx, slice.editor.scrollPane).forEach { part ->
            part.addMouseWheelListener { e ->
                if (!slice.scrollable) canvas.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, canvas))
            }
        }
        slice.editor.caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                val id = slices.entries.firstOrNull { it.value === slice }?.key ?: return
                if (focused == id) canvas.focusedOffset = slice.editor.caretModel.offset
                scheduleHighlight(id, slice.editor.caretModel.offset)
            }
        })
        slice.editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                focusMovedTo(slices.entries.firstOrNull { it.value === slice }?.key)
            }

            override fun focusLost(e: FocusEvent) {
                // Completion popups and the like keep focus semantics in the editor; real moves go elsewhere.
                if (!e.isTemporary && slices.values.none { it.editor.contentComponent.isFocusOwner }) focusMovedTo(null)
            }
        })
        slices[block.id] = slice
        return slice
    }

    /** Where a link lands inside its expanded target block: the middle of the target method's first line. */
    private fun targetLineY(link: dev.codelanes.model.Link): Int? {
        val range = link.targetRange ?: return null
        val slice = slices[link.to] ?: return null
        val editor = slice.editor
        if (range.start > editor.document.textLength) return null
        val xy = editor.offsetToXY(range.start)
        return SwingUtilities.convertPoint(editor.contentComponent, xy.x, xy.y + editor.lineHeight / 2, canvas).y
    }

    private fun summaryOf(block: Block): JComponent {
        summaries[block.id]?.takeIf { it.first == block.summary }?.let { return it.second }
        val lines = block.summary.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        val label = JBLabel("<html>$lines</html>").apply {
            border = JBUI.Borders.empty(4, 8)
            foreground = JBColor.GRAY
        }
        summaries[block.id] = block.summary to label
        return label
    }

    private fun fontSize(): Int = (baseFontSize * canvas.zoom).roundToInt().coerceAtLeast(2)

    override fun blockMoved(id: String, position: Point) = withModelAccess {
        if (id.startsWith(NOTE_PREFIX)) {
            notesFile.move(file.path, id.removePrefix(NOTE_PREFIX), position.x, position.y)
            relayout()
            return@withModelAccess
        }
        PinStore.getInstance(project).pin(file.path, id, position)
        relayout()
    }

    override fun collapseToggled(id: String) = withModelAccess {
        collapsed[id] = !(collapsed[id] ?: false)
        render()
    }

    override fun zoomChanged() = withModelAccess {
        slices.values.forEach { it.setFontSize(fontSize()) }
        render()
    }

    /** Forgets every dragged position in this file and goes back to the automatic lanes. */
    override fun tidyUp() = resetLayout()

    override fun layoutModeChosen(mode: dev.codelanes.layout.LayoutMode) =
        dev.codelanes.settings.BlocksSettings.instance.setMode(mode)

    /** Runs a CodeLanes IDE action (Follow a Request…, working sets) from the canvas toolbar. */
    override fun runAction(id: String) {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val action = manager.getAction(id) ?: return
        manager.tryToExecute(action, null, canvas, "CodeLanesCanvas", true)
    }

    /**
     * Called when focus enters a block (id) or leaves all blocks (null). A rebuild that was held back because it
     * would pull a half-typed method out of the focused block runs as soon as focus moves on.
     */
    fun focusMovedTo(id: String?) {
        if (focused == id) return
        focused = id
        canvas.focusedId = id
        canvas.focusedOffset = id?.let { slices[it] }?.editor?.caretModel?.offset
        if (heldBack) {
            heldBack = false
            rebuildNow()
        }
    }

    fun resetLayout() = withModelAccess {
        PinStore.getInstance(project).clear(file.path)
        relayout()
    }

    /** The block of this file that shows [offset], if any. */
    fun blockAt(offset: Int): String? {
        val current = model ?: return null
        return current.blocks
            .filter { it.filePath == file.path && it.kind != BlockKind.MORE }
            .mapNotNull { block -> tracked[block.id]?.current()?.let { block to it } }
            .filter { (_, ranges) ->
                val (range, excluded) = ranges
                offset in range.start..range.end && excluded.none { offset >= it.start && offset < it.end }
            }
            .minByOrNull { (_, ranges) -> ranges.first.end - ranges.first.start }
            ?.first?.id
    }

    /**
     * Shows the code at [offset] of this file: picks the block that displays it (expanding it if collapsed),
     * centres it on the canvas, focuses it and puts the caret there. Used by go-to-declaration.
     */
    fun reveal(offset: Int) = withModelAccess {
        val target = model?.block(blockAt(offset) ?: return@withModelAccess) ?: return@withModelAccess
        if (collapsed[target.id] == true) {
            collapsed[target.id] = false
            render()
        }
        canvas.centerOn(target.id)
        focusMovedTo(target.id)
        slices[target.id]?.let { slice ->
            slice.editor.caretModel.moveToOffset(offset)
            slice.editor.contentComponent.requestFocusInWindow()
        }
    }

    /** Lights up the blocks that define and use the symbol at [offset] in block [id] (synchronous). */
    fun highlightUsagesAt(id: String, offset: Int) {
        val input = highlightInput(id) ?: return run { canvas.highlights = emptyMap() }
        canvas.highlights = ReadAction.compute<Map<String, UsageHighlight.Level>, RuntimeException> { computeHighlights(input, offset) }
    }

    /** Everything the background search needs, copied on the EDT so it never touches the session's own maps. */
    private class HighlightInput(val document: Document, val spans: List<UsageHighlight.Span>)

    private fun highlightInput(id: String): HighlightInput? {
        val slice = slices[id] ?: return null
        val current = model ?: return null
        val spans = current.blocks.mapNotNull { block ->
            tracked[block.id]?.current()?.let { (range, excluded) -> UsageHighlight.Span(block.id, block.filePath, range, excluded) }
        }
        return HighlightInput(slice.editor.document, spans)
    }

    private fun scheduleHighlight(id: String, offset: Int) {
        highlightAlarm.cancelAllRequests()
        highlightAlarm.addRequest({
            if (disposed) return@addRequest
            val input = highlightInput(id) ?: return@addRequest
            ReadAction.nonBlocking<Map<String, UsageHighlight.Level>> { computeHighlights(input, offset) }
                .withDocumentsCommitted(project)
                .inSmartMode(project)
                .expireWith(this)
                .coalesceBy(this, highlightAlarm)
                .finishOnUiThread(ModalityState.defaultModalityState()) { canvas.highlights = it }
                .submit(AppExecutorUtil.getAppExecutorService())
        }, HIGHLIGHT_DELAY_MS)
    }

    private fun computeHighlights(input: HighlightInput, offset: Int): Map<String, UsageHighlight.Level> {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(input.document) ?: return emptyMap()
        val target = symbolAt(psiFile, offset) ?: return emptyMap()
        val spans = input.spans
        val files = spans.map { it.filePath }.distinct()
            .mapNotNull { path -> file.fileSystem.findFileByPath(path)?.let { PsiManager.getInstance(project).findFile(it) } }
        if (files.isEmpty()) return emptyMap()
        val usages = ReferencesSearch.search(target, LocalSearchScope(files.toTypedArray())).findAll().mapNotNull { ref ->
            val path = ref.element.containingFile?.virtualFile?.path ?: return@mapNotNull null
            UsageHighlight.Spot(path, ref.element.textRange.startOffset + ref.rangeInElement.startOffset, definition = false)
        }
        val definition = target.containingFile?.virtualFile?.path?.let { path ->
            val at = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: target.textOffset
            UsageHighlight.Spot(path, at, definition = true)
        }
        return UsageHighlight.assign(usages + listOfNotNull(definition), spans)
    }

    /** The symbol under the caret: what a reference there points to, or the declaration whose name is there. */
    private fun symbolAt(psiFile: com.intellij.psi.PsiFile, offset: Int): com.intellij.psi.PsiElement? {
        psiFile.findReferenceAt(offset)?.resolve()?.let { return it }
        val element = psiFile.findElementAt(offset) ?: return null
        val owner = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, false) ?: return null
        return owner.takeIf { it.nameIdentifier?.textRange?.containsOffset(offset) == true }
    }

    /**
     * "+ method": adds an empty method (a signature in an interface) at the end of the class with a free name,
     * shows it as its own block and selects the name so you can type over it.
     */
    fun addMethod() = withModelAccess {
        val current = model ?: return@withModelAccess
        val cls = current.ofKind(BlockKind.CLASS).firstOrNull() ?: return@withModelAccess
        val (range, _) = tracked[cls.id]?.current() ?: return@withModelAccess
        val taken = current.ofKind(BlockKind.METHOD).map { it.id.removePrefix("method:").lowercase() }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "newMethod" else "newMethod$it" }.first { it.lowercase() !in taken }
        val indent = "    "
        val method = if (cls.title.startsWith("interface")) "${indent}public function $name(): void;\n"
        else "${indent}public function $name(): void\n$indent{\n$indent}\n"
        // The class range can have grown past its brace (text typed after it): find the brace itself.
        val closingBrace = document.charsSequence.lastIndexOf('}', range.end - 1)
        if (closingBrace < range.start) return@withModelAccess
        val lineStart = dev.codelanes.editor.SliceRanges.lineStartOf(document.charsSequence, closingBrace)
        val insertAt = if (lineStart < closingBrace && document.charsSequence.subSequence(lineStart, closingBrace).isBlank()) lineStart else closingBrace
        val text = "\n" + method
        WriteCommandAction.runWriteCommandAction(project, "Add Method", null, { document.insertString(insertAt, text) })
        focusMovedTo(null)
        rebuildNow()
        val nameOffset = insertAt + text.indexOf(name)
        reveal(nameOffset)
        slices["method:$name"]?.editor?.selectionModel?.setSelection(nameOffset, nameOffset + name.length)
    }

    /** Deletes method block [id] (its whole lines) after [confirm] says yes (asked before taking any lock). */
    fun deleteMethod(id: String, confirm: () -> Boolean = { askToDelete(id) }) {
        if (!confirm()) return
        withModelAccess { deleteLines(id) }
    }

    private fun deleteLines(id: String) {
        val current = model ?: return
        val cls = current.ofKind(BlockKind.CLASS).firstOrNull() ?: return
        val (classRange, _) = tracked[cls.id]?.current() ?: return
        val (range, _) = tracked[id]?.current() ?: return
        val lines = dev.codelanes.editor.SliceRanges.wholeLines(document.charsSequence, classRange, range)
        WriteCommandAction.runWriteCommandAction(project, "Delete Method", null, { document.deleteString(lines.start, lines.end) })
        focusMovedTo(null)
        rebuildNow()
    }

    private fun askToDelete(id: String): Boolean {
        val title = model?.blocks?.firstOrNull { it.id == id }?.title ?: id
        return Messages.showYesNoDialog(project, "Delete method $title?", "Delete Method", null) == Messages.YES
    }

    private fun followAction(id: String): Pair<String, () -> Unit> =
        (if (id in followedCalls) "− calls" else "+ calls") to { toggleCalls(id) }

    private fun actionsFor(block: Block): Pair<List<Pair<String, () -> Unit>>, List<Pair<String, () -> Unit>>> = when (block.kind) {
        BlockKind.CLASS -> listOf<Pair<String, () -> Unit>>("+ method" to { addMethod() }) to emptyList()
        BlockKind.METHOD -> listOf(followAction(block.id)) to listOf<Pair<String, () -> Unit>>("Delete method" to { deleteMethod(block.id) })
        BlockKind.CALLEE -> listOf(followAction(block.id)) to
            if (block.id.removePrefix("callee:") in added) listOf<Pair<String, () -> Unit>>("Remove from canvas" to { removeFromCanvas(block.id) }) else emptyList()
        BlockKind.PARENT, BlockKind.INTERFACE, BlockKind.TRAIT ->
            listOf<Pair<String, () -> Unit>>((if (block.id in revealed) "− parents" else "+ parents") to { toggleReveal(block.id) }) to emptyList()
        else -> emptyList<Pair<String, () -> Unit>>() to emptyList()
    }

    /** Moves the caret into the next (down) or previous block that has an editor, in reading order. */
    fun moveFocus(from: String, down: Boolean): Boolean {
        val rects = views.mapValues { (_, v) -> dev.codelanes.layout.Rect(v.x, v.y, v.width, v.height) }
        val target = dev.codelanes.layout.BlockNavigation.next(rects, from, down, slices.keys) ?: return false
        val slice = slices[target] ?: return false
        val (range, _) = tracked[target]?.current() ?: return false
        focusMovedTo(target)
        slice.editor.caretModel.moveToOffset(if (down) range.start else range.end)
        slice.editor.contentComponent.requestFocusInWindow()
        return true
    }

    /** "+ parents" / "− parents": shows or hides the parents, interfaces and traits of a related block. */
    fun toggleReveal(id: String) = withModelAccess {
        if (!revealed.remove(id)) revealed += id
        focusMovedTo(null)
        rebuildNow()
    }

    fun summaryText(id: String): String = (summaries[id]?.second as? JBLabel)?.text.orEmpty()

    /** "+ calls" / "− calls": shows or hides the methods that [id] calls in other classes. */
    fun toggleCalls(id: String) = withModelAccess {
        if (!followedCalls.remove(id)) followedCalls += id
        focusMovedTo(null)
        rebuildNow()
    }

    /** Sets (or with null clears) the review mark of block [id]; remembers the code it was given for. */
    fun markBlock(id: String, mark: ReviewMark?) = withModelAccess {
        val block = model?.blocks?.firstOrNull { it.id == id } ?: return@withModelAccess
        val note = reviews.get(block.filePath, id)?.note.orEmpty()
        reviews.put(block.filePath, id, ReviewEntry(mark, note, codeHash(block)))
        render()
    }

    /** Sets the review note of block [id] (blank removes it), keeping its mark. */
    fun setNote(id: String, note: String) = withModelAccess {
        val block = model?.blocks?.firstOrNull { it.id == id } ?: return@withModelAccess
        val current = reviews.get(block.filePath, id)
        reviews.put(block.filePath, id, ReviewEntry(current?.mark, note.trim(), current?.hash ?: codeHash(block)))
        render()
    }

    private fun editNote(id: String) {
        val block = model?.blocks?.firstOrNull { it.id == id } ?: return
        val current = reviews.get(block.filePath, id)?.note.orEmpty()
        val note = Messages.showMultilineInputDialog(project, "Note for ${block.title}", "Review Note", current, null, null) ?: return
        setNote(id, note)
    }

    /** Fingerprint of the code a block shows right now (its range minus the parts other blocks show). */
    private fun codeHash(block: Block): String {
        val (range, excluded) = tracked[block.id]?.current() ?: return ""
        val text = documentOf(block)?.charsSequence ?: return ""
        val visible = StringBuilder()
        for (offset in range.start until minOf(range.end, text.length)) {
            if (excluded.none { it.contains(offset) }) visible.append(text[offset])
        }
        return Review.hash(visible)
    }

    private fun reviewMenu(block: Block): List<Pair<String, () -> Unit>> = listOf(
        "✓ Understood" to { markBlock(block.id, ReviewMark.UNDERSTOOD) },
        "? Don't understand" to { markBlock(block.id, ReviewMark.UNCLEAR) },
        "! Needs change" to { markBlock(block.id, ReviewMark.NEEDS_CHANGE) },
        "Clear review mark" to { markBlock(block.id, null) },
        "Edit review note…" to { editNote(block.id) },
    )

    /** This canvas's state, for a working set. */
    fun snapshot(): CanvasState =
        CanvasState(file.path, followedCalls.sorted(), revealed.sorted(), collapsed.toSortedMap(), canvas.zoom, added.sorted())

    /** Opens this canvas up the way [state] describes (followed calls, revealed parents, collapsed blocks, zoom). */
    fun restore(state: CanvasState) = withModelAccess {
        followedCalls.clear()
        followedCalls += state.followedCalls
        revealed.clear()
        revealed += state.revealed
        added.clear()
        added += state.added
        collapsed.clear()
        collapsed += state.collapsed
        canvas.setZoom(state.zoom)
        focusMovedTo(null)
        rebuildNow()
        render()
    }

    /**
     * Follows the calls of [from] automatically, [depth] levels deep (also into the implementations of interface
     * methods), then focuses [from]. Replaces whatever was followed before. Used for "Follow a Request…".
     */
    fun followChain(from: String, depth: Int) = withModelAccess {
        // A new request replaces the previous chain on this canvas.
        followedCalls.clear()
        followedCalls += from
        var frontier = setOf(from)
        for (level in 1 until depth) {
            focusMovedTo(null)
            rebuildNow()
            val links = model?.links.orEmpty()
            val callees = links.filter { it.kind == LinkKind.CALLS_INTO && it.from in frontier }.map { it.to }.toSet()
            val implementations = links.filter { it.kind == LinkKind.IMPLEMENTED_BY && it.from in callees }.map { it.to }
            val next = (callees + implementations) - followedCalls
            if (next.isEmpty()) break
            followedCalls += next
            frontier = next
        }
        focusMovedTo(null)
        rebuildNow()
        tracked[from]?.current()?.let { (range, _) -> reveal(range.start) }
    }

    /** Re-places the blocks, e.g. after the vertical-layout switch changed. */
    fun refreshLayout() = withModelAccess { relayout() }

    /** Drops method [method] of [classFqn] (found with search) on this canvas, centred and focused. */
    fun addToCanvas(classFqn: String, method: String) = withModelAccess {
        // A method of the open class already has its own block: just go there.
        val own = model?.blocks?.firstOrNull { it.id == "method:$method" }
        if (own != null && model?.blocks?.any { it.id == "class:$classFqn" } == true) {
            tracked[own.id]?.current()?.let { (range, _) -> reveal(range.start) }
            return@withModelAccess
        }
        val key = "$classFqn::$method"
        val id = "callee:$key"
        added += key
        focusMovedTo(null)
        rebuildNow()
        if (id !in views) {
            added -= key
            return@withModelAccess
        }
        collapsed[id] = false
        canvas.centerOn(id)
        focusMovedTo(id)
        slices[id]?.editor?.contentComponent?.requestFocusInWindow()
    }

    /** Takes a block that was added from search off the canvas again. */
    fun removeFromCanvas(id: String) = withModelAccess {
        added -= id.removePrefix("callee:")
        followedCalls -= id
        focusMovedTo(null)
        rebuildNow()
    }

    /** "+ Note": a free-standing note on this canvas (shared in .codelanes/notes.json). Returns its id. */
    fun addNote(text: String): String {
        val id = notesFile.add(file.path, text)
        withModelAccess { render() }
        return id
    }

    override fun addNote() {
        addNote("")
    }

    fun noteText(id: String): String = notesFile.notes(file.path).firstOrNull { it.id == id }?.text.orEmpty()

    fun linkNote(id: String, blockId: String) {
        notesFile.link(file.path, id, blockId)
        withModelAccess { render() }
    }

    fun deleteNote(id: String) {
        notesFile.delete(file.path, id)
        views.remove(NOTE_PREFIX + id)
        noteAreas.remove(id)
        withModelAccess { render() }
    }

    /** Note cards: an editable text area in a block, saved shortly after typing stops. */
    private fun renderNotes() {
        val notes = notesFile.notes(file.path)
        val ids = notes.map { NOTE_PREFIX + it.id }.toSet()
        views.keys.filter { it.startsWith(NOTE_PREFIX) && it !in ids }.forEach {
            views.remove(it)
            noteAreas.remove(it.removePrefix(NOTE_PREFIX))
        }
        for (note in notes) {
            val id = NOTE_PREFIX + note.id
            val view = views.getOrPut(id) { BlockView(id, canvas) }
            val area = noteAreas.getOrPut(note.id) {
                com.intellij.ui.components.JBTextArea(note.text, 3, 28).apply {
                    lineWrap = true
                    wrapStyleWord = true
                    background = NOTE_BACKGROUND
                    border = JBUI.Borders.empty(6, 8)
                    document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                        override fun textChanged(e: javax.swing.event.DocumentEvent) = scheduleNoteSave(note.id)
                    })
                }
            }
            if (!area.hasFocus() && area.text != note.text) area.text = note.text
            val isCollapsed = collapsed[id] ?: false
            val block = Block(id, BlockKind.NOTE, "Note", file.path, SourceRange(0, 0), collapsed = isCollapsed,
                summary = listOf(note.text.lineSequence().firstOrNull().orEmpty()))
            view.update(block, isCollapsed, if (isCollapsed) summaryOf(block) else area, canvas.zoom)
            view.setActions(emptyList(), noteMenu(note.id))
            view.setReview(null, "")
        }
    }

    private fun scheduleNoteSave(id: String) {
        noteAlarm.cancelAllRequests()
        noteAlarm.addRequest({ noteAreas[id]?.let { notesFile.setText(file.path, id, it.text) } }, NOTE_SAVE_DELAY_MS)
    }

    private fun noteLinks(): List<dev.codelanes.model.Link> {
        val present = views.keys
        return notesFile.notes(file.path).flatMap { note ->
            note.links.filter { it in present }.map { dev.codelanes.model.Link(LinkKind.NOTE, NOTE_PREFIX + note.id, it) }
        }
    }

    /** Where notes go: where they were dragged, or else stacked to the right of everything else. */
    private fun noteRects(blocks: Map<String, dev.codelanes.layout.Rect>): Map<String, dev.codelanes.layout.Rect> {
        var freeX = (blocks.values.maxOfOrNull { it.right } ?: 0) + LayoutEngine.H_GAP
        var freeY = 0
        return notesFile.notes(file.path).mapNotNull { note ->
            val id = NOTE_PREFIX + note.id
            val size = views[id]?.naturalSize(canvas.zoom) ?: return@mapNotNull null
            val rect = if (note.x != null && note.y != null) {
                dev.codelanes.layout.Rect(note.x, note.y, size.width, size.height)
            } else {
                dev.codelanes.layout.Rect(freeX, freeY, size.width, size.height).also { freeY += size.height + LayoutEngine.V_GAP }
            }
            id to rect
        }.toMap()
    }

    private fun noteMenu(id: String): List<Pair<String, () -> Unit>> = listOf(
        "Link to block…" to { chooseBlockToLink(id) },
        "Remove links" to {
            notesFile.unlinkAll(file.path, id)
            withModelAccess { render() }
        },
        "Delete note" to {
            if (Messages.showYesNoDialog(project, "Delete this note?", "Delete Note", null) == Messages.YES) deleteNote(id)
        },
    )

    private fun chooseBlockToLink(id: String) {
        val blocks = model?.blocks.orEmpty().filter { it.kind != BlockKind.MORE }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(blocks)
            .setTitle("Link Note to Block")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.title })
            .setNamerForFiltering { it.title }
            .setItemChosenCallback { linkNote(id, it.id) }
            .createPopup()
            .showInCenterOf(canvas)
    }

    fun view(id: String): BlockView? = views[id]

    fun blockIds(): List<String> = views.keys.toList()
    fun hasSliceEditor(id: String): Boolean = id in slices
    fun sliceEditor(id: String): SliceEditor? = slices[id]
    fun viewBounds(id: String): java.awt.Rectangle? = views[id]?.bounds

    override fun dispose() {
        disposed = true
        tracked.values.forEach(TrackedBlock::dispose)
        tracked.clear()
    }

    /** A block's range and excluded ranges, kept up to date by range markers between builds. */
    private class TrackedBlock(document: Document, block: Block) {
        private val range = document.createRangeMarker(block.range.start, block.range.end).apply { isGreedyToRight = true }
        private val excluded = block.excluded.map { document.createRangeMarker(it.start, it.end).apply { isGreedyToRight = true } }

        fun current(): Pair<SourceRange, List<SourceRange>>? {
            if (!range.isValid || excluded.any { !it.isValid }) return null
            return SourceRange(range.startOffset, range.endOffset) to excluded.map { SourceRange(it.startOffset, it.endOffset) }
        }

        fun dispose() {
            range.dispose()
            excluded.forEach(RangeMarker::dispose)
        }
    }

    companion object {
        const val REBUILD_DELAY_MS = 300
        const val NOTE_SAVE_DELAY_MS = 500
        const val NOTE_PREFIX = "note:"
        private val NOTE_BACKGROUND = JBColor(java.awt.Color(0xFFF8E1), java.awt.Color(0x3A3628))
        const val HIGHLIGHT_DELAY_MS = 200
    }
}
