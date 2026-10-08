package com.readcodelikeahuman.canvas

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.readcodelikeahuman.layout.ArrowGeometry
import com.readcodelikeahuman.layout.LinkRoutes
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
        fun tidyUp()
    }

    var zoom: Double = 1.0
        private set
    var focusedId: String? = null
        set(value) { field = value; repaint() }
    var notice: String? = null
        set(value) { field = value; repaint() }

    internal val tidyButton = javax.swing.JButton("Tidy up")
    private val pan = java.awt.Point(40, 40)
    private val views = linkedMapOf<String, BlockView>()
    private var rects: Map<String, Rect> = emptyMap()
    private var links: List<Link> = emptyList()

    init {
        isOpaque = true
        isFocusable = true
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        val panner = object : MouseAdapter() {
            private var last: java.awt.Point? = null

            override fun mousePressed(e: MouseEvent) {
                last = e.point
                requestFocusInWindow() // clicking the background leaves the block you were typing in
            }
            override fun mouseReleased(e: MouseEvent) { last = null }

            override fun mouseDragged(e: MouseEvent) {
                val l = last ?: return
                pan.translate(e.x - l.x, e.y - l.y)
                last = e.point
                moveViews()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                when {
                    e.isMetaDown || e.isControlDown -> setZoom(zoom * 1.1.pow(-e.preciseWheelRotation))
                    e.isShiftDown -> { pan.translate((-e.preciseWheelRotation * 40).roundToInt(), 0); moveViews() }
                    else -> { pan.translate(0, (-e.preciseWheelRotation * 40).roundToInt()); moveViews() }
                }
            }
        }
        tidyButton.toolTipText = "Put every block back in its lane (forgets dragged positions)"
        tidyButton.addActionListener { listener.tidyUp() }
        add(tidyButton)
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
        links.filter { it.kind !in FOCUS_ONLY || focusedId == it.from || focusedId == it.to }

    /** Panning only moves blocks; no re-layout of the editors inside them, which keeps scrolling smooth. */
    private fun moveViews() {
        for ((id, view) in views) {
            val r = rects[id] ?: continue
            view.setLocation(pan.x + (r.x * zoom).roundToInt(), pan.y + (r.y * zoom).roundToInt())
        }
        repaint()
    }

    override fun doLayout() {
        val size = tidyButton.preferredSize
        tidyButton.setBounds(width - size.width - 12, 8, size.width, size.height)
    }

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

    /** Lines are painted over the blocks, so a line is never hidden behind one. */
    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for ((link, route) in routes(visibleLinks())) {
                g2.color = colorFor(link)
                g2.stroke = strokeFor(link.kind)
                g2.drawPolyline(route.map { it.x }.toIntArray(), route.map { it.y }.toIntArray(), route.size)
                arrowHead(g2, route[route.size - 2], route.last())
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

    private var cachedKey: Any? = null
    private var cachedRoutes: Map<Link, List<Point>> = emptyMap()

    /** Routes are computed relative to the pan offset and cached, so panning only translates them. */
    private fun routes(visible: List<Link>): Map<Link, List<Point>> {
        val relative = views.mapValues { (_, v) -> Rect(v.x - pan.x, v.y - pan.y, v.width, v.height) }
        val key = Triple(relative, visible, zoom)
        if (key != cachedKey) {
            cachedRoutes = LinkRoutes.compute(
                visible,
                relative,
                (ArrowGeometry.LOOP * zoom).roundToInt(),
                (SLOT_STEP * zoom).roundToInt().coerceAtLeast(4),
            )
            cachedKey = key
        }
        return cachedRoutes.mapValues { (_, route) -> route.map { Point(it.x + pan.x, it.y + pan.y) } }
    }

    private fun arrowHead(g2: Graphics2D, from: Point, to: Point) {
        val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
        val size = 8 * zoom
        val xs = intArrayOf(to.x, (to.x - size * cos(angle - PI / 7)).roundToInt(), (to.x - size * cos(angle + PI / 7)).roundToInt())
        val ys = intArrayOf(to.y, (to.y - size * sin(angle - PI / 7)).roundToInt(), (to.y - size * sin(angle + PI / 7)).roundToInt())
        g2.fillPolygon(xs, ys, 3)
    }

    /** Override lines each get their own colour (stable per line), so lines shown together never look alike. */
    internal fun colorFor(link: Link): java.awt.Color {
        if (link.kind != LinkKind.OVERRIDES) return colorFor(link.kind)
        val overrides = links.filter { it.kind == LinkKind.OVERRIDES }.sortedWith(compareBy({ it.from }, { it.to }))
        return OVERRIDE_PALETTE[overrides.indexOf(link).coerceAtLeast(0) % OVERRIDE_PALETTE.size]
    }

    private fun colorFor(kind: LinkKind) = when (kind) {
        LinkKind.OWNS -> JBColor.GRAY
        LinkKind.CALLS -> JBColor.BLUE
        else -> JBColor.foreground()
    }

    private fun strokeFor(kind: LinkKind): BasicStroke {
        val width = (1.5 * zoom).toFloat()
        val dash = when (kind) {
            LinkKind.IMPLEMENTS, LinkKind.OVERRIDES -> floatArrayOf(8f, 6f)
            LinkKind.USES -> floatArrayOf(2f, 4f)
            LinkKind.INJECTS -> floatArrayOf(10f, 4f, 2f, 4f)
            else -> null
        }
        return BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
    }

    private fun Rectangle.toRect() = Rect(x, y, width, height)

    companion object {
        const val SLOT_STEP = 12

        /** Calls and overrides only show for the focused block; otherwise the canvas drowns in lines. */
        private val FOCUS_ONLY = setOf(LinkKind.CALLS, LinkKind.OVERRIDES)

        private val OVERRIDE_PALETTE = listOf(
            JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0x6AAB73)),
            JBColor(java.awt.Color(0xC2185B), java.awt.Color(0xF06292)),
            JBColor(java.awt.Color(0xEF6C00), java.awt.Color(0xFFB74D)),
            JBColor(java.awt.Color(0x6A1B9A), java.awt.Color(0xBA68C8)),
            JBColor(java.awt.Color(0x00838F), java.awt.Color(0x4DD0E1)),
            JBColor(java.awt.Color(0x9E9D24), java.awt.Color(0xDCE775)),
        )
        const val MIN_ZOOM = 0.2
        const val MAX_ZOOM = 2.0
    }
}
