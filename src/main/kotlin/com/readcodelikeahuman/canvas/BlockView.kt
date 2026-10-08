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
class BlockView(var id: String, private val canvas: BlocksCanvas) : JPanel(BorderLayout()) {
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

    /** True when the content is taller than the cap, so the body has to scroll. */
    fun overflows(zoom: Double): Boolean = preferredSize.height / zoom > MAX_HEIGHT

    companion object {
        const val MIN_WIDTH = 160
        const val MAX_WIDTH = 900
        const val MAX_HEIGHT = 600
    }
}
