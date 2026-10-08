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
