package dev.codelanes.canvas

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import dev.codelanes.layout.ArrowGeometry
import dev.codelanes.layout.LabelPlacement
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
        fun layoutModeChosen(mode: dev.codelanes.layout.LayoutMode) {}
        fun runAction(id: String) {}
        fun addNote() {}
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

    /** Bottom-left toolbar: layout switch, Follow a Request…, Working sets ▾, Tidy up. */
    internal val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
    private val modeButtons = linkedMapOf(
        dev.codelanes.layout.LayoutMode.LANES to javax.swing.JToggleButton("Lanes"),
        dev.codelanes.layout.LayoutMode.COLUMN to javax.swing.JToggleButton("Column"),
        dev.codelanes.layout.LayoutMode.TREE to javax.swing.JToggleButton("Tree"),
    )
    internal val followButton = javax.swing.JButton("Follow a Request…")
    private val addButton = javax.swing.JButton("Add method…")
    private val noteButton = javax.swing.JButton("+ Note")
    private val setsButton = javax.swing.JButton("Working sets ▾")

    internal fun toolbarTexts(): List<String> =
        toolbar.components.flatMap { c -> if (c is JPanel) c.components.toList() else listOf(c) }
            .mapNotNull { (it as? javax.swing.AbstractButton)?.text }

    internal fun modeButton(mode: dev.codelanes.layout.LayoutMode): javax.swing.JToggleButton = modeButtons.getValue(mode)

    /** Shows which layout is active. */
    fun setLayoutMode(mode: dev.codelanes.layout.LayoutMode) {
        modeButtons[mode]?.isSelected = true
    }

    private fun buildToolbar() {
        val group = javax.swing.ButtonGroup()
        val switch = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        for ((mode, button) in modeButtons) {
            group.add(button)
            button.toolTipText = when (mode) {
                dev.codelanes.layout.LayoutMode.LANES -> "Lanes left to right: what the class builds on, the class, its methods"
                dev.codelanes.layout.LayoutMode.COLUMN -> "Every block in one column, top to bottom"
                dev.codelanes.layout.LayoutMode.TREE -> "Each level a row; lines from the bottom of a block into the top of the next"
            }
            button.addActionListener { listener.layoutModeChosen(mode) }
            switch.add(button)
        }
        modeButtons.getValue(dev.codelanes.layout.LayoutMode.LANES).isSelected = true
        followButton.toolTipText = "Pick a route and see the whole chain of code it runs"
        followButton.addActionListener { listener.runAction("CodeLanes.FollowRequest") }
        addButton.toolTipText = "Find any method in the project and drop it on this canvas"
        addButton.addActionListener { listener.runAction("CodeLanes.AddMethodToCanvas") }
        noteButton.toolTipText = "Add a note to this canvas (shared with your team in .codelanes/notes.json)"
        noteButton.addActionListener { listener.addNote() }
        setsButton.toolTipText = "Save or reopen the canvases you opened for a task or review"
        setsButton.addActionListener {
            val menu = javax.swing.JPopupMenu()
            listOf(
                "Save Working Set…" to "CodeLanes.SaveWorkingSet",
                "Open Working Set…" to "CodeLanes.OpenWorkingSet",
                "Delete Working Set…" to "CodeLanes.DeleteWorkingSet",
            ).forEach { (text, id) -> menu.add(javax.swing.JMenuItem(text).apply { addActionListener { listener.runAction(id) } }) }
            menu.show(setsButton, 0, -menu.preferredSize.height) // opens upwards
        }
        toolbar.add(switch)
        toolbar.add(followButton)
        toolbar.add(addButton)
        toolbar.add(noteButton)
        toolbar.add(setsButton)
        toolbar.add(tidyButton)
        add(toolbar)
    }

    /** Tree layout: lines go out of the bottom of a block into the top of the next. */
    var tree = false

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
        buildToolbar()
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
            if (view.dragging) continue
            view.setLocation(pan.x + (r.x * zoom).roundToInt(), pan.y + (r.y * zoom).roundToInt())
        }
        repaint()
    }

    override fun doLayout() {
        val size = toolbar.preferredSize
        // Bottom-left, right above the editor's Blocks | Text tabs; the legend sits above it.
        toolbar.setBounds(12, height - size.height - 8, size.width, size.height)
    }

    private fun placeViews() {
        for ((id, view) in views) {
            val r = rects[id] ?: continue
            if (view.dragging) continue
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
            val allRoutes = routes(visibleLinks())
            for ((link, route) in allRoutes) {
                val drawn = drawnRoute(link, route)
                g2.color = colorFor(link)
                g2.stroke = strokeFor(link.kind)
                g2.drawPolyline(drawn.map { it.x }.toIntArray(), drawn.map { it.y }.toIntArray(), drawn.size)
                arrowHead(g2, drawn[drawn.size - 2], drawn.last())
                labelFor(link)?.let { text ->
                    val others = allRoutes.filterKeys { it != link }.values.flatMap { it.zipWithNext() }
                    val atStart = when {
                        link.kind in POINTS_AT_RELATED -> false // next to the arrowhead: "… extends → Model"
                        link.kind !in FOCUS_ONLY -> true
                        else -> link.from == focusedId
                    }
                    paintLineLabel(g2, text, drawn, atStart, others)
                }
            }
            paintLegend(g2)
            notice?.let { paintNotice(g2, it) }
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
        val key = listOf(relative, visible, zoom, targetY, tree)
        if (key != cachedKey) {
            cachedRoutes = LinkRoutes.compute(
                visible,
                relative,
                (ArrowGeometry.LOOP * zoom).roundToInt(),
                (SLOT_STEP * zoom).roundToInt().coerceAtLeast(4),
                targetY,
                tree,
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
     * Call lines: blue shades for "the focused method calls …", purple shades for "… calls the focused method",
     * one shade per line. Override lines: green shades, one per line. Other kinds use their kind colour.
     */
    internal fun colorFor(link: Link): java.awt.Color = when (link.kind) {
        LinkKind.CALLS -> {
            val outgoing = link.from == focusedId || link.to != focusedId
            val siblings = links.filter { it.kind == LinkKind.CALLS && if (outgoing) it.from == link.from else it.to == link.to }
                .sortedWith(compareBy({ it.from }, { it.to }))
            val palette = if (outgoing) CALLS_OUT else CALLS_IN
            palette[siblings.indexOf(link).coerceAtLeast(0) % palette.size]
        }
        LinkKind.OVERRIDES -> {
            val overrides = links.filter { it.kind == LinkKind.OVERRIDES }.sortedWith(compareBy({ it.from }, { it.to }))
            OVERRIDE_PALETTE[overrides.indexOf(link).coerceAtLeast(0) % OVERRIDE_PALETTE.size]
        }
        else -> colorFor(link.kind)
    }

    /**
     * Small word on a line. Structural lines say what they are (next to the block they start from); call and
     * override lines are read from the focused method's point of view. "Has method" lines stay unlabelled.
     */
    internal fun labelFor(link: Link): String? = when {
        link.kind == LinkKind.EXTENDS -> "extends"
        link.kind == LinkKind.IMPLEMENTS -> "implements"
        link.kind == LinkKind.USES -> "uses trait"
        link.kind == LinkKind.INJECTS -> "injected"
        link.kind == LinkKind.IMPLEMENTED_BY -> "implemented by"
        link.kind == LinkKind.CALLS_INTO -> "calls"
        link.kind == LinkKind.NOTE -> "note"
        link.kind == LinkKind.CALLS && link.from == focusedId -> "calls"
        link.kind == LinkKind.CALLS && link.to == focusedId -> "called by"
        link.kind == LinkKind.OVERRIDES && link.from == focusedId -> "overrides"
        link.kind == LinkKind.OVERRIDES && link.to == focusedId -> "overridden by"
        else -> null
    }

    private fun colorFor(kind: LinkKind): java.awt.Color = KIND_COLORS.getValue(kind)

    /** All lines are solid: colour carries the meaning (people read colours far better than dash patterns). */
    internal fun strokeFor(kind: LinkKind): BasicStroke {
        val width = (if (kind == LinkKind.OWNS) 1.2 * zoom else 1.8 * zoom).toFloat()
        // Note links aren't code relations: the one dashed line, so they never look like one.
        val dash = if (kind == LinkKind.NOTE) floatArrayOf(6f, 5f) else null
        return BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
    }

    /**
     * Writes [text] just above the line segment next to the focused block, in the line's colour, sliding along the
     * segment until no [others] line runs through it. Without a free stretch it gets a small background patch.
     */
    private fun paintLineLabel(g2: Graphics2D, text: String, route: List<Point>, atStart: Boolean, others: List<Pair<Point, Point>>) {
        // Labels sit above a horizontal stretch: the one nearest the chosen end (tree routes start vertically).
        val ordered = if (atStart) route else route.reversed()
        val (a, b) = ordered.zipWithNext().firstOrNull { (p, q) -> p.y == q.y && p.x != q.x } ?: (ordered[0] to ordered[1])
        g2.font = JBFont.small()
        val metrics = g2.fontMetrics
        val width = metrics.stringWidth(text)
        val free = LabelPlacement.place(a, b, width, metrics.ascent, others)
        val at = free ?: Point(if (b.x >= a.x) a.x + 6 else a.x - 6 - width, a.y - 4)
        if (free == null) {
            val lineColour = g2.color
            g2.color = background
            g2.fillRect(at.x - 2, at.y - metrics.ascent, width + 4, metrics.ascent + 2)
            g2.color = lineColour
        }
        g2.drawString(text, at.x, at.y)
    }

    /**
     * Lines to what the class builds on are drawn from the class to it (arrowhead at the parent, interface, trait or
     * dependency), so they read like the code: "Order extends Model". The model links run the other way for layout.
     */
    internal fun drawnRoute(link: Link, route: List<Point>): List<Point> =
        if (link.kind in POINTS_AT_RELATED) route.reversed() else route

    /** A calm banner at the top centre (not red text): the canvas is waiting for valid code, nothing is wrong. */
    private fun paintNotice(g2: Graphics2D, text: String) {
        g2.font = JBFont.label()
        val metrics = g2.fontMetrics
        val padding = 10
        val w = metrics.stringWidth(text) + 2 * padding
        val h = metrics.height + padding
        val x = (width - w) / 2
        val y = 8
        g2.color = JBColor.namedColor("Banner.infoBackground", JBColor(java.awt.Color(0xE8F0FE), java.awt.Color(0x25324D)))
        g2.fillRoundRect(x, y, w, h, 10, 10)
        g2.color = JBColor.namedColor("Banner.infoBorderColor", JBColor(java.awt.Color(0x9DB6E8), java.awt.Color(0x35538F)))
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(x, y, w, h, 10, 10)
        g2.color = JBColor.foreground()
        g2.drawString(text, x + padding, y + padding / 2 + metrics.ascent)
    }

    /** Small key in the bottom-left corner: which colour means which kind of line. */
    private fun paintLegend(g2: Graphics2D) {
        g2.font = JBFont.small()
        val metrics = g2.fontMetrics
        val lineHeight = metrics.height + 2
        val entries = LEGEND.filter { (kind, _) -> links.any { it.kind == kind } }
        val directionalRows = (if (links.any { it.kind == LinkKind.CALLS }) 2 else 0) + (if (links.any { it.kind == LinkKind.OVERRIDES }) 1 else 0)
        val bottom = toolbar.y - 10
        var y = bottom - lineHeight * (entries.size + directionalRows - 1)
        g2.stroke = BasicStroke(2.5f)
        for ((kind, label) in entries) {
            g2.color = colorFor(kind)
            g2.drawLine(12, y - metrics.ascent / 2, 32, y - metrics.ascent / 2)
            g2.color = JBColor.foreground()
            g2.drawString(label, 40, y)
            y += lineHeight
        }
        val directional = listOf(
            Triple(LinkKind.CALLS, CALLS_OUT.first(), "calls →"),
            Triple(LinkKind.CALLS, CALLS_IN.first(), "← called by"),
            Triple(LinkKind.OVERRIDES, OVERRIDE_PALETTE.first(), "overrides / implements method"),
        ).filter { (kind, _, _) -> links.any { it.kind == kind } }
        for ((_, colour, label) in directional) {
            g2.color = colour
            g2.drawLine(12, y - metrics.ascent / 2, 32, y - metrics.ascent / 2)
            g2.color = JBColor.foreground()
            g2.drawString(label, 40, y)
            y += lineHeight
        }
    }

    private fun Rectangle.toRect() = Rect(x, y, width, height)

    companion object {
        const val SLOT_STEP = 12

        /** Calls and overrides only show for the focused block; otherwise the canvas drowns in lines. */
        private val FOCUS_ONLY = setOf(LinkKind.CALLS, LinkKind.OVERRIDES)

        private val POINTS_AT_RELATED = setOf(LinkKind.EXTENDS, LinkKind.IMPLEMENTS, LinkKind.USES, LinkKind.INJECTS)

        /** "This method calls …": blue/cyan shades, one per line. */
        internal val CALLS_OUT = listOf(
            JBColor(java.awt.Color(0x0277BD), java.awt.Color(0x4FC3F7)),
            JBColor(java.awt.Color(0x283593), java.awt.Color(0x9FA8DA)),
            JBColor(java.awt.Color(0x006064), java.awt.Color(0x80DEEA)),
        )

        /** "This method is called by …": purple/magenta shades, one per line. */
        internal val CALLS_IN = listOf(
            JBColor(java.awt.Color(0xAD1457), java.awt.Color(0xF06292)),
            JBColor(java.awt.Color(0x6A1B9A), java.awt.Color(0xBA68C8)),
            JBColor(java.awt.Color(0xC2185B), java.awt.Color(0xF8BBD0)),
            JBColor(java.awt.Color(0x4A148C), java.awt.Color(0xD1C4E9)),
        )

        /** Override lines: green shades (they belong with "implements"). Never orange/yellow: those mark highlights. */
        internal val OVERRIDE_PALETTE = listOf(
            JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0xA5D6A7)),
            JBColor(java.awt.Color(0x1B5E20), java.awt.Color(0x66BB6A)),
            JBColor(java.awt.Color(0x558B2F), java.awt.Color(0x9CCC65)),
        )

        private val KIND_COLORS = mapOf(
            LinkKind.EXTENDS to JBColor(java.awt.Color(0x1565C0), java.awt.Color(0x64B5F6)),
            LinkKind.IMPLEMENTS to JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0x81C784)),
            LinkKind.USES to JBColor(java.awt.Color(0x6D4C41), java.awt.Color(0xBCAAA4)),
            LinkKind.INJECTS to JBColor(java.awt.Color(0x455A64), java.awt.Color(0x90A4AE)),
            LinkKind.OWNS to JBColor(java.awt.Color(0x757575), java.awt.Color(0x9E9E9E)),
            LinkKind.CALLS to CALLS_OUT.first(),
            LinkKind.OVERRIDES to OVERRIDE_PALETTE.first(),
            LinkKind.IMPLEMENTED_BY to JBColor(java.awt.Color(0x00695C), java.awt.Color(0x80CBC4)),
            // Its own cyan, not one of the per-line "calls" shades.
            LinkKind.CALLS_INTO to JBColor(java.awt.Color(0x00838F), java.awt.Color(0x4DD0E1)),
            LinkKind.NOTE to JBColor(java.awt.Color(0x8D8D8D), java.awt.Color(0x8F8F8F)),
        )

        private val LEGEND = listOf(
            LinkKind.EXTENDS to "extends",
            LinkKind.IMPLEMENTS to "implements",
            LinkKind.USES to "uses trait",
            LinkKind.INJECTS to "injected",
            LinkKind.OWNS to "has method",
            LinkKind.IMPLEMENTED_BY to "implemented / used by",
            LinkKind.CALLS_INTO to "calls into another class",
            LinkKind.NOTE to "note (dashed)",
        )
        const val MIN_ZOOM = 0.2
        const val MAX_ZOOM = 2.0
    }
}
