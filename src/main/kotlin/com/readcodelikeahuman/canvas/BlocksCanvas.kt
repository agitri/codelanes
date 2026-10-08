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
                val route = ArrowGeometry.route(a.toRect(), b.toRect(), (ArrowGeometry.LOOP * zoom).roundToInt())
                g2.color = colorFor(link.kind)
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
        const val MIN_ZOOM = 0.2
        const val MAX_ZOOM = 2.0
    }
}
