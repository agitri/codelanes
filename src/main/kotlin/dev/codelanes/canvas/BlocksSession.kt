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
import dev.codelanes.model.SourceRange
import dev.codelanes.php.PhpBlockBuilder
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

    init {
        canvas.targetLineY = ::targetLineY
        watch(document)
        rebuildNow()
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
        ReadAction.nonBlocking<CanvasUpdate?> { computeUpdate(previous) }
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
        if (!documents.isCommitted(document)) {
            // Committing is a model change; e.g. inside FileEditorProvider.createEditor it isn't allowed.
            if (!TransactionGuard.getInstance().isWritingAllowed) {
                scheduleRebuild()
                return
            }
            documents.commitDocument(document)
        }
        if (DumbService.isDumb(project)) {
            scheduleRebuild()
            return
        }
        ReadAction.compute<CanvasUpdate?, RuntimeException> { computeUpdate(model) }?.let(::applyUpdate)
    }

    private fun computeUpdate(previous: BlockModel?): CanvasUpdate? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return RebuildPolicy.next(previous, PhpBlockBuilder.build(psi), PsiTreeUtil.hasErrorElements(psi))
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
    }

    /** While someone types in a block, don't move what they're typing into a new block under their caret. */
    private fun wouldPullTextOutOfFocusedBlock(next: BlockModel): Boolean {
        val id = focused ?: return false
        val now = tracked[id]?.current() ?: return false
        val after = next.blocks.firstOrNull { it.id == id } ?: return false
        return after.excluded.size > now.second.size
    }

    /** UI events arrive on the EDT without a read lock; documents, editors and folds need one. */
    private fun withModelAccess(action: () -> Unit) = WriteIntentReadAction.run(Runnable(action))

    /** Takes over a freshly built model: decides which blocks exist and re-tracks their ranges. */
    private fun adopt(next: BlockModel) {
        val live = next.blocks.map { it.id }.toSet()
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
        val old = vanished.singleOrNull() ?: return
        val new = appeared.singleOrNull() ?: return
        val wasMethod = model?.blocks?.firstOrNull { it.id == old }?.kind == BlockKind.METHOD
        if (!wasMethod || next.block(new).kind != BlockKind.METHOD) return
        views.remove(old)?.let { it.id = new; views[new] = it }
        slices.remove(old)?.let { slices[new] = it }
        collapsed.remove(old)?.let { collapsed[new] = it }
        summaries.remove(old)
        tracked.remove(old)?.dispose()
        val pins = PinStore.getInstance(project)
        pins.pins(file.path)[old]?.let { pins.pin(file.path, new, it) }
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
            actionsFor(block).let { (actions, menu) -> view.setActions(actions, menu) }
        }
        canvas.setContent(LinkedHashMap(views), current.links)
        relayout()
        slices.forEach { (id, slice) -> slice.setScrollable(views[id]?.overflows(canvas.zoom) == true) }
    }

    private fun relayout() {
        val current = model ?: return
        val pins = PinStore.getInstance(project).pins(file.path)
        val layout = LayoutEngine.layout(current, { views.getValue(it.id).naturalSize(canvas.zoom) }, pins)
        canvas.place(layout.rects)
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

    /**
     * Shows the code at [offset] of this file: picks the block that displays it (expanding it if collapsed),
     * centres it on the canvas, focuses it and puts the caret there. Used by go-to-declaration.
     */
    fun reveal(offset: Int) = withModelAccess {
        val current = model ?: return@withModelAccess
        val target = current.blocks
            .filter { it.filePath == file.path && it.kind != BlockKind.MORE }
            .mapNotNull { block -> tracked[block.id]?.current()?.let { block to it } }
            .filter { (_, ranges) ->
                val (range, excluded) = ranges
                offset in range.start..range.end && excluded.none { offset >= it.start && offset < it.end }
            }
            .minByOrNull { (_, ranges) -> ranges.first.end - ranges.first.start }
            ?.first ?: return@withModelAccess
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
        canvas.highlights = ReadAction.compute<Map<String, UsageHighlight.Level>, RuntimeException> { computeHighlights(id, offset) }
    }

    private fun scheduleHighlight(id: String, offset: Int) {
        highlightAlarm.cancelAllRequests()
        highlightAlarm.addRequest({
            if (disposed) return@addRequest
            ReadAction.nonBlocking<Map<String, UsageHighlight.Level>> { computeHighlights(id, offset) }
                .inSmartMode(project)
                .expireWith(this)
                .coalesceBy(this, highlightAlarm)
                .finishOnUiThread(ModalityState.defaultModalityState()) { canvas.highlights = it }
                .submit(AppExecutorUtil.getAppExecutorService())
        }, HIGHLIGHT_DELAY_MS)
    }

    private fun computeHighlights(id: String, offset: Int): Map<String, UsageHighlight.Level> {
        val slice = slices[id] ?: return emptyMap()
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(slice.editor.document) ?: return emptyMap()
        val target = symbolAt(psiFile, offset) ?: return emptyMap()
        val current = model ?: return emptyMap()
        val spans = current.blocks.mapNotNull { block ->
            tracked[block.id]?.current()?.let { (range, excluded) -> UsageHighlight.Span(block.id, block.filePath, range, excluded) }
        }
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
        val closingBrace = range.end - 1
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

    /** Deletes method block [id] (its whole lines) after [confirm] says yes. */
    fun deleteMethod(id: String, confirm: () -> Boolean = { askToDelete(id) }) = withModelAccess {
        val current = model ?: return@withModelAccess
        val cls = current.ofKind(BlockKind.CLASS).firstOrNull() ?: return@withModelAccess
        val (classRange, _) = tracked[cls.id]?.current() ?: return@withModelAccess
        val (range, _) = tracked[id]?.current() ?: return@withModelAccess
        if (!confirm()) return@withModelAccess
        val lines = dev.codelanes.editor.SliceRanges.wholeLines(document.charsSequence, classRange, range)
        WriteCommandAction.runWriteCommandAction(project, "Delete Method", null, { document.deleteString(lines.start, lines.end) })
        focusMovedTo(null)
        rebuildNow()
    }

    private fun askToDelete(id: String): Boolean {
        val title = model?.blocks?.firstOrNull { it.id == id }?.title ?: id
        return Messages.showYesNoDialog(project, "Delete method $title?", "Delete Method", null) == Messages.YES
    }

    private fun actionsFor(block: Block): Pair<List<Pair<String, () -> Unit>>, List<Pair<String, () -> Unit>>> = when (block.kind) {
        BlockKind.CLASS -> listOf<Pair<String, () -> Unit>>("+ method" to { addMethod() }) to emptyList()
        BlockKind.METHOD -> emptyList<Pair<String, () -> Unit>>() to listOf<Pair<String, () -> Unit>>("Delete method" to { deleteMethod(block.id) })
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
        const val HIGHLIGHT_DELAY_MS = 200
    }
}
