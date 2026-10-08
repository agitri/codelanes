package dev.codelanes.canvas

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import dev.codelanes.layout.ArrowGeometry
import dev.codelanes.layout.LinkRoutes
import dev.codelanes.layout.Point
import dev.codelanes.layout.Rect
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
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
    /** Blocks lit up for the symbol under the caret (definition / usage). */
    var highlights: Map<String, UsageHighlight.Level> = emptyMap()
        set(value) {
            field = value
            views.forEach { (id, view) -> view.setHighlight(value[id]) }
            repaint()
        }

    /** Caret offset inside the focused block's file, if known. */
    var focusedOffset: Int? = null
        set(value) { field = value; repaint() }

    /** Screen y of the line a link lands on inside its (expanded) target block, if any. */
    var targetLineY: (Link) -> Int? = { null }
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
        views.forEach { (id, view) -> view.setHighlight(highlights[id]) }
        this.links = links
    }

    fun place(rects: Map<String, Rect>) {
        this.rects = rects
        placeViews()
    }

    fun toCanvas(screen: java.awt.Point): Point =
        Point(((screen.x - pan.x) / zoom).roundToInt(), ((screen.y - pan.y) / zoom).roundToInt())

    internal fun visibleLinks(): List<Link> =
        links.filter { link ->
            when {
                link.kind !in FOCUS_ONLY -> true
                focusedId == link.from -> true
                focusedId != link.to -> false
                // In a parent or interface: only the method under the caret shows its override line.
                link.kind == LinkKind.OVERRIDES -> link.targetRange?.let { r -> focusedOffset?.let { it in r.start..r.end } } ?: false
                else -> true
            }
        }

    /** Pans so that block [id] sits in the middle of the canvas. */
    fun centerOn(id: String) {
        val view = views[id] ?: return
        pan.translate(width / 2 - (view.x + view.width / 2), height / 2 - (view.y + view.height / 2))
        moveViews()
    }

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
            paintLegend(g2)
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
        val targetY = visible.mapNotNull { link -> targetLineY(link)?.let { link to it - pan.y } }.toMap()
        val key = listOf(relative, visible, zoom, targetY)
        if (key != cachedKey) {
            cachedRoutes = LinkRoutes.compute(
                visible,
                relative,
                (ArrowGeometry.LOOP * zoom).roundToInt(),
                (SLOT_STEP * zoom).roundToInt().coerceAtLeast(4),
                targetY,
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

    /**
     * Call and override lines each get their own colour (stable per line), so e.g. "total calls subtotal" and
     * "validate calls total" never look alike. Other kinds use their kind colour.
     */
    internal fun colorFor(link: Link): java.awt.Color {
        if (link.kind !in FOCUS_ONLY) return colorFor(link.kind)
        val perLine = links.filter { it.kind in FOCUS_ONLY }.sortedWith(compareBy({ it.kind }, { it.from }, { it.to }))
        return OVERRIDE_PALETTE[perLine.indexOf(link).coerceAtLeast(0) % OVERRIDE_PALETTE.size]
    }

    private fun colorFor(kind: LinkKind): java.awt.Color = KIND_COLORS.getValue(kind)

    /** All lines are solid: colour carries the meaning (people read colours far better than dash patterns). */
    internal fun strokeFor(kind: LinkKind): BasicStroke =
        BasicStroke((if (kind == LinkKind.OWNS) 1.2 * zoom else 1.8 * zoom).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    /** Small key in the bottom-left corner: which colour means which kind of line. */
    private fun paintLegend(g2: Graphics2D) {
        g2.font = JBFont.small()
        val metrics = g2.fontMetrics
        val lineHeight = metrics.height + 2
        val entries = LEGEND.filter { (kind, _) -> links.any { it.kind == kind } }
        val perLine = links.any { it.kind in FOCUS_ONLY }
        var y = height - 12 - lineHeight * (entries.size - if (perLine) 0 else 1)
        g2.stroke = BasicStroke(2.5f)
        for ((kind, label) in entries) {
            g2.color = colorFor(kind)
            g2.drawLine(12, y - metrics.ascent / 2, 32, y - metrics.ascent / 2)
            g2.color = JBColor.foreground()
            g2.drawString(label, 40, y)
            y += lineHeight
        }
        if (perLine) {
            OVERRIDE_PALETTE.take(3).forEachIndexed { i, colour ->
                g2.color = colour
                g2.drawLine(12 + i * 7, y - metrics.ascent / 2, 17 + i * 7, y - metrics.ascent / 2)
            }
            g2.color = JBColor.foreground()
            g2.drawString("calls / overrides (one colour per line, shown for the block you're in)", 40, y)
        }
    }

    private fun Rectangle.toRect() = Rect(x, y, width, height)

    companion object {
        const val SLOT_STEP = 12

        /** Calls and overrides only show for the focused block; otherwise the canvas drowns in lines. */
        private val FOCUS_ONLY = setOf(LinkKind.CALLS, LinkKind.OVERRIDES)

        private val KIND_COLORS = mapOf(
            LinkKind.EXTENDS to JBColor(java.awt.Color(0x1565C0), java.awt.Color(0x64B5F6)),
            LinkKind.IMPLEMENTS to JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0x81C784)),
            LinkKind.USES to JBColor(java.awt.Color(0xB28704), java.awt.Color(0xFFD54F)),
            LinkKind.INJECTS to JBColor(java.awt.Color(0x6A1B9A), java.awt.Color(0xCE93D8)),
            LinkKind.OWNS to JBColor(java.awt.Color(0x757575), java.awt.Color(0x9E9E9E)),
            LinkKind.CALLS to JBColor(java.awt.Color(0xC62828), java.awt.Color(0xEF9A9A)),
            LinkKind.OVERRIDES to JBColor(java.awt.Color(0xD81B60), java.awt.Color(0xF48FB1)),
            LinkKind.IMPLEMENTED_BY to JBColor(java.awt.Color(0x00695C), java.awt.Color(0x80CBC4)),
        )

        /** Override lines each get one of these; none of them is a kind colour above. */
        private val OVERRIDE_PALETTE = listOf(
            JBColor(java.awt.Color(0xD81B60), java.awt.Color(0xF48FB1)),
            JBColor(java.awt.Color(0xEF6C00), java.awt.Color(0xFFB74D)),
            JBColor(java.awt.Color(0x00838F), java.awt.Color(0x4DD0E1)),
            JBColor(java.awt.Color(0x827717), java.awt.Color(0xDCE775)),
            JBColor(java.awt.Color(0x283593), java.awt.Color(0x9FA8DA)),
            JBColor(java.awt.Color(0x5D4037), java.awt.Color(0xBCAAA4)),
        )

        private val LEGEND = listOf(
            LinkKind.EXTENDS to "extends",
            LinkKind.IMPLEMENTS to "implements",
            LinkKind.USES to "uses trait",
            LinkKind.INJECTS to "injected",
            LinkKind.OWNS to "has method",
            LinkKind.IMPLEMENTED_BY to "implemented / used by",
        )
        const val MIN_ZOOM = 0.2
        const val MAX_ZOOM = 2.0
    }
}
