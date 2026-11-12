package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * The clickable group header shown in the editor tab bar (HDR-1..HDR-4).
 *
 * Hosted as a direct child of the split's tab component (never inside a tab label, so that `DragHelper` does not mistake
 * a press on it for a tab drag). The space it needs is reserved inside a tab label (see [TabLabelSpace]); the adapter then
 * places this component over that space, above the labels. It only paints and reports mouse gestures.
 * Click handling lives in [HeaderMouseHandler]; dragging a header is done by `HeaderDragHelper` (a glass-pane `MouseDragHelper`).
 *
 * [bounds] are the *visible* part of the pill in tab-bar coordinates (empty = not shown: scrolled out of the strip). When
 * the pill is cut at the edge of the strip, [setFullArea] tells it how big it really is, and it paints cut instead of squeezed.
 */
internal class GroupHeader(val groupId: String, private val style: () -> StripStyle = { StripStyle.DEFAULT }) : JComponent() {
    var decoration: GroupDecoration? = null
        set(value) {
            field = value
            styleChanged()
        }

    /** The current style changed (or the decoration did): refreshes the tooltip (HDR-3), the length of the pill and the paint. */
    fun styleChanged() {
        val max = maxNameLength()
        toolTipText = decoration?.name?.takeIf { it.length > max }
        revalidate()
        repaint()
    }

    private fun maxNameLength(): Int = style().maxNameLength.coerceAtLeast(1)

    var listener: StripEvents? = null

    /**
     * While true the pill does not take part in hit testing. The platform tab drag looks for the label under the dragged
     * tab with `findComponentAt`; the pill must not hide the host label from it. Set by the adapter (platform drag active).
     */
    internal var transparentToHitTest: () -> Boolean = { false }

    /** Called at the start of every paint; the adapter uses it to notice that a platform tab drag started. */
    internal var onPaint: (() -> Unit)? = null

    /** True while the group is being dragged: its place is the drop slot, nothing of the pill is drawn in the strip. */
    var suppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    private var fullWidth = 0
    private var fullHeight = 0
    private var offsetX = 0
    private var offsetY = 0

    init {
        isOpaque = false
        // Marker understood by JBTabsImpl.applyResetComponents: "this component has been laid out", keep our bounds.
        putClientProperty("Layout.done", true)
        HeaderMouseHandler(this).also {
            addMouseListener(it)
            addMouseMotionListener(it)
        }
    }

    /** The text drawn in the pill: the name, cut at the style's maximum length with an ellipsis (HDR-3). */
    internal val displayName: String
        get() = maxNameLength().let { max -> decoration?.name.orEmpty().let { if (it.length > max) it.take(max) + "…" else it } }

    private val isDot: Boolean get() = decoration?.name.isNullOrEmpty()

    /** Length along the tab strip that this header needs, including a small gap on both sides. This is the space reserved in the host label. */
    fun pillLength(): Int {
        val pill = if (isDot) JBUI.scale(DOT_SIZE) + JBUI.scale(8) else getFontMetrics(headerFont()).stringWidth(displayName) + JBUI.scale(2 * PAD)
        return pill + JBUI.scale(2 * GAP)
    }

    override fun getPreferredSize(): Dimension = Dimension(pillLength(), JBUI.scale(28))

    override fun contains(x: Int, y: Int): Boolean = !transparentToHitTest() && super.contains(x, y)

    /** The pill's whole area and how far its visible part ([getBounds]) is shifted into it; paints cut, not squeezed. */
    fun setFullArea(width: Int, height: Int, offsetX: Int, offsetY: Int) {
        if (fullWidth == width && fullHeight == height && this.offsetX == offsetX && this.offsetY == offsetY) return
        fullWidth = width
        fullHeight = height
        this.offsetX = offsetX
        this.offsetY = offsetY
        repaint()
    }

    /**
     * The pill as painted (the filled shape: the rounded rectangle, or the dot of an unnamed group), in this component's coordinates.
     * The component also covers a small gap on each side of the pill, and the pill may be cut at the edge of the strip, so this can
     * reach outside the component's bounds. For anchoring the group popup to the pill's leading edge (§6).
     */
    fun pillBounds(): Rectangle {
        val w0 = if (fullWidth > 0) fullWidth else width
        val h0 = if (fullHeight > 0) fullHeight else height
        val gap = JBUI.scale(GAP)
        val vInset = JBUI.scale(V_INSET)
        val r = if (isDot) {
            val dot = JBUI.scale(DOT_SIZE)
            Rectangle((w0 - dot) / 2, (h0 - dot) / 2, dot, dot)
        }
        else {
            Rectangle(gap, vInset, w0 - 2 * gap, h0 - 2 * vInset)
        }
        r.translate(-offsetX, -offsetY)
        return r
    }

    private fun headerFont() = JBUI.CurrentTheme.EditorTabs.font()

    override fun paintComponent(g: Graphics) {
        onPaint?.invoke()
        if (suppressed) return
        val d = decoration ?: return
        val w0 = if (fullWidth > 0) fullWidth else width
        val h0 = if (fullHeight > 0) fullHeight else height
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(-offsetX, -offsetY)
            val gap = JBUI.scale(GAP).toDouble()
            val vInset = JBUI.scale(V_INSET).toDouble()
            if (isDot) {
                val dot = JBUI.scale(DOT_SIZE).toDouble()
                g2.color = d.color
                g2.fill(Ellipse2D.Double((w0 - dot) / 2, (h0 - dot) / 2, dot, dot))
            }
            else {
                val w = w0 - 2 * gap
                val h = h0 - 2 * vInset
                g2.color = ColorUtil.withAlpha(d.color, style().opacity.coerceIn(0.0, 1.0))
                g2.fill(RoundRectangle2D.Double(gap, vInset, w, h, h, h))
                g2.font = headerFont()
                g2.color = UIUtil.getLabelForeground()
                val fm = g2.fontMetrics
                val text = displayName
                val tx = gap + (w - fm.stringWidth(text)) / 2
                val ty = vInset + (h - fm.height) / 2 + fm.ascent
                g2.drawString(text, tx.toFloat(), ty.toFloat())
            }
        }
        finally {
            g2.dispose()
        }
    }

    companion object {
        private const val PAD = 8
        private const val GAP = 4
        private const val V_INSET = 4
        private const val DOT_SIZE = 10
    }
}

/**
 * The single place where header mouse gestures are interpreted. Left, middle and right click are reported on release when
 * the pointer has not moved more than [CLICK_SLOP]. Every event is consumed, and since the header is a mouse-listening
 * child of the tab component, Swing delivers the event to it and not to the tab component, so
 * `EditorTabbedContainer`'s double-click-to-maximise listener (INT-2) never sees it.
 */
internal class HeaderMouseHandler(private val header: GroupHeader) : MouseAdapter() {
    private var pressPoint: java.awt.Point? = null
    private var pressButton = 0

    override fun mousePressed(e: MouseEvent) {
        pressPoint = e.point
        pressButton = e.button
        e.consume()
    }

    override fun mouseReleased(e: MouseEvent) {
        val start = pressPoint
        pressPoint = null
        e.consume()
        if (start == null || e.button != pressButton) return
        if (start.distance(e.point) > CLICK_SLOP) return
        val listener = header.listener ?: return
        when {
            e.isPopupTrigger || e.button == MouseEvent.BUTTON3 -> listener.headerRightClicked(header, e)
            e.button == MouseEvent.BUTTON2 -> listener.headerMiddleClicked(header, e)
            e.button == MouseEvent.BUTTON1 -> listener.headerClicked(header, e)
        }
    }

    override fun mouseClicked(e: MouseEvent) {
        e.consume() // swallow click counts, including double clicks
    }

    override fun mouseDragged(e: MouseEvent) {
        // The drag itself is run by HeaderDragHelper (glass pane, see TabBarDnd); this only keeps the event away from the tab bar.
        e.consume()
    }

    companion object {
        const val CLICK_SLOP = 5.0
    }
}
