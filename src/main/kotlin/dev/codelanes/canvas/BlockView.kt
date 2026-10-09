package dev.codelanes.canvas

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.codelanes.layout.Size
import dev.codelanes.model.Block
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.roundToInt

/** One block on the canvas: a title bar (click = collapse/expand, drag = move) above its body. */
class BlockView(var id: String, private val canvas: BlocksCanvas) : JPanel(BorderLayout()) {
    private val title = JBLabel()
    internal val titleBar = JPanel(BorderLayout())
    private var body: JComponent? = null
    private val badge = JBLabel()
    private val note = JBLabel().apply {
        foreground = JBColor.GRAY
        isVisible = false
    }
    private val actionsPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
    private var actions: List<Pair<String, () -> Unit>> = emptyList()
    private var menu: List<Pair<String, () -> Unit>> = emptyList()

    init {
        setHighlight(null)
        titleBar.border = JBUI.Borders.empty(4, 8)
        titleBar.background = JBColor.namedColor("EditorTabs.background", JBColor.PanelBackground)
        titleBar.add(badge, BorderLayout.WEST)
        titleBar.add(title, BorderLayout.CENTER)
        titleBar.add(note, BorderLayout.SOUTH)
        badge.border = JBUI.Borders.emptyRight(6)
        titleBar.add(actionsPanel, BorderLayout.EAST)
        add(titleBar, BorderLayout.NORTH)

        val mover = object : MouseAdapter() {
            private var start: java.awt.Point? = null
            private var origin: java.awt.Point? = null
            private var dragged = false

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                start = e.locationOnScreen
                origin = location
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val s = start ?: return
                val o = origin ?: return
                val dx = e.locationOnScreen.x - s.x
                val dy = e.locationOnScreen.y - s.y
                // A click with a little wobble is still a click, not a drag (it would pin the block).
                if (!dragged && kotlin.math.abs(dx) < DRAG_THRESHOLD && kotlin.math.abs(dy) < DRAG_THRESHOLD) return
                setLocation(o.x + e.locationOnScreen.x - s.x, o.y + e.locationOnScreen.y - s.y)
                dragged = true
                dragging = true
                canvas.repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) return showMenu(e)
                dragging = false
                if (dragged) canvas.listener.blockMoved(id, canvas.toCanvas(location))
                start = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!dragged) canvas.listener.collapseToggled(id)
            }
        }
        titleBar.addMouseListener(mover)
        titleBar.addMouseMotionListener(mover)
    }


    /** Links on the right of the title bar (e.g. "+ method") and items of the title bar's right-click menu. */
    fun setActions(actions: List<Pair<String, () -> Unit>>, menu: List<Pair<String, () -> Unit>>) {
        actionsPanel.removeAll()
        actions.forEach { (text, run) -> actionsPanel.add(com.intellij.ui.components.ActionLink(text) { run() }) }
        actionsPanel.revalidate()
        this.actions = actions
        this.menu = menu
    }

    /** True while the user drags this block; relayouts leave it where the mouse is. */
    var dragging = false


    /** Review badge left of the title (✓ ? ! or ~ when the code changed since) and the note under it. */
    fun setReview(status: dev.codelanes.review.ReviewStatus?, noteText: String) {
        val (symbol, colour) = when (status) {
            dev.codelanes.review.ReviewStatus.UNDERSTOOD -> "✓" to JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0x81C784))
            dev.codelanes.review.ReviewStatus.UNCLEAR -> "?" to JBColor(java.awt.Color(0x6A1B9A), java.awt.Color(0xCE93D8))
            dev.codelanes.review.ReviewStatus.NEEDS_CHANGE -> "!" to JBColor(java.awt.Color(0xC62828), java.awt.Color(0xEF9A9A))
            dev.codelanes.review.ReviewStatus.CHANGED -> "~" to JBColor.GRAY
            null -> "" to JBColor.foreground()
        }
        badge.text = symbol
        badge.foreground = colour
        badge.toolTipText = when (status) {
            dev.codelanes.review.ReviewStatus.CHANGED -> "Changed since it was reviewed"
            null -> null
            else -> status.name.lowercase().replace('_', ' ')
        }
        rawNote = noteText
        // Notes come from a shared file: always shown as text (escaped), never interpreted as HTML.
        note.text = if (noteText.isBlank()) "" else
            "<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(noteText).replace("\n", "<br>") + "</html>"
        note.isVisible = noteText.isNotBlank()
    }

    internal fun reviewBadge(): String = badge.text
    private var rawNote = ""

    internal fun noteText(): String = rawNote
    internal fun noteLabelText(): String = note.text

    internal fun actionTexts(): List<String> = actions.map { it.first }
    internal fun menuTexts(): List<String> = menu.map { it.first }

    private fun showMenu(e: MouseEvent) {
        if (menu.isEmpty()) return
        val popup = javax.swing.JPopupMenu()
        menu.forEach { (text, run) -> popup.add(javax.swing.JMenuItem(text).apply { addActionListener { run() } }) }
        popup.show(e.component, e.x, e.y)
    }

    /** Coloured border while the symbol under the caret is defined (strong) or used (light) in this block. */
    fun setHighlight(level: UsageHighlight.Level?) {
        // Always 3px in total, so a highlight never shrinks (and clips) the block's contents.
        border = when (level) {
            UsageHighlight.Level.DEFINITION -> JBUI.Borders.customLine(DEFINITION_COLOR, 3)
            UsageHighlight.Level.USAGE -> javax.swing.BorderFactory.createCompoundBorder(JBUI.Borders.customLine(USAGE_COLOR, 2), JBUI.Borders.empty(1))
            null -> javax.swing.BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(2))
        }
    }

    fun update(block: Block, collapsed: Boolean, body: JComponent, zoom: Double) {
        title.text = (if (collapsed) "▸ " else "▾ ") + block.title
        title.font = JBFont.label().asBold().deriveFont((JBFont.label().size2D * zoom).toFloat())
        badge.font = title.font
        note.font = JBFont.label().asItalic().deriveFont((JBFont.label().size2D * zoom).toFloat())
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

    /** True when the content is taller than the cap, so the body has to scroll. */
    fun overflows(zoom: Double): Boolean = preferredSize.height / zoom > MAX_HEIGHT

    companion object {
        const val DRAG_THRESHOLD = 4
        internal val DEFINITION_COLOR = JBColor(java.awt.Color(0xE65100), java.awt.Color(0xFFA726))
        internal val USAGE_COLOR = JBColor(java.awt.Color(0xF9A825), java.awt.Color(0xFFF176))
        const val MIN_WIDTH = 160
        const val MAX_WIDTH = 900
        const val MAX_HEIGHT = 600
    }
}
