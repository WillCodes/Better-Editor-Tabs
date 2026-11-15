package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.service.GroupColors
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import javax.swing.ToolTipManager

/**
 * The colour row of the group popup: the nine palette colours as filled circles in enum order. The current colour is marked with
 * a ring in its own colour (Chrome style), hover shows a faint ring, keyboard focus a ring in the platform focus colour.
 *
 * One Tab stop for the whole row. Left / Right move among the swatches, Space or Enter applies the focused one ([onPick]) and keeps the
 * popup open, so colours can be tried one after another (unlike Enter elsewhere in the popup, where Enter closes it). A click applies at once and leaves
 * the popup open and the focus where it was; the mark moves immediately.
 * Tooltip and accessible description are the colour's name.
 */
internal class ColorSwatches(
    current: GroupColor,
    private val onPick: (GroupColor) -> Unit,
) : JComponent() {
    private val colors = GroupColor.values()
    private var selected: GroupColor = current
    private var focusedIndex = current.ordinal
    private var hoverIndex = -1

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        ToolTipManager.sharedInstance().registerComponent(this)
        // getAccessibleContext(), not `accessibleContext`: in a JComponent subclass Kotlin resolves that name to Swing's protected
        // field, which is null until the getter creates the context.
        getAccessibleContext().accessibleName = BetterEditorTabsBundle.message("popup.colors.accessibleName")
        updateDescription()
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = hover(indexAt(e))
            override fun mouseExited(e: MouseEvent) = hover(-1)
            override fun mouseReleased(e: MouseEvent) {
                val index = indexAt(e)
                // The focus stays where it is (normally the name field): a mouse pick shows only the "current" ring, not a
                // focus ring as well, and typing can go on.
                if (e.button == MouseEvent.BUTTON1 && index >= 0) {
                    focusedIndex = index
                    apply(colors[index])
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        bindKey("LEFT") { moveFocus(-1) }
        bindKey("RIGHT") { moveFocus(1) }
        // Space and Enter both apply the focused colour and keep the popup open, so the user can keep trying colours (the one exception to
        // "Enter closes the popup"). Escape or a click outside closes.
        bindKey("SPACE") { apply(colors[focusedIndex]) }
        bindKey("ENTER") { apply(colors[focusedIndex]) }
    }

    private fun apply(color: GroupColor) {
        if (color != selected) {
            selected = color
            updateDescription()
            repaint()
        }
        onPick(color)
    }

    private fun moveFocus(delta: Int) {
        focusedIndex = (focusedIndex + delta).coerceIn(0, colors.size - 1)
        repaint()
    }

    private fun hover(index: Int) {
        if (index != hoverIndex) {
            hoverIndex = index
            repaint()
        }
    }

    private fun updateDescription() {
        getAccessibleContext().accessibleDescription = BetterEditorTabsBundle.message(selected.messageKey)
    }

    private fun cell(): Int = JBUI.scale(CELL)

    private fun indexAt(e: MouseEvent): Int {
        if (e.x < 0 || e.y !in 0 until height) return -1
        return (e.x / cell()).takeIf { it < colors.size } ?: -1
    }

    override fun getToolTipText(event: MouseEvent): String? =
        indexAt(event).takeIf { it >= 0 }?.let { BetterEditorTabsBundle.message(colors[it].messageKey) }

    override fun getPreferredSize(): Dimension = Dimension(cell() * colors.size, cell())

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val cell = cell().toDouble()
            val radius = JBUIScale.scale(CIRCLE / 2f).toDouble()
            for ((i, color) in colors.withIndex()) {
                val cx = cell * i + cell / 2
                val cy = height / 2.0
                val paint: Color = GroupColors.jbColor(color)
                g2.color = paint
                g2.fill(Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2))
                val markRadius = radius + JBUIScale.scale(RING_GAP)
                if (color == selected) ring(g2, cx, cy, markRadius, 1.5f, paint)
                else if (i == hoverIndex) ring(g2, cx, cy, markRadius, 1.5f, faint(paint))
                if (i == focusedIndex && isFocusOwner) {
                    ring(g2, cx, cy, radius + JBUIScale.scale(FOCUS_GAP), 2f, JBUI.CurrentTheme.Focus.focusColor())
                }
            }
        } finally {
            g2.dispose()
        }
    }

    /** A ring whose centre line has radius [r], so it sits outside the circle. */
    private fun ring(g2: Graphics2D, cx: Double, cy: Double, r: Double, stroke: Float, color: Color) {
        g2.color = color
        g2.stroke = BasicStroke(JBUIScale.scale(stroke))
        g2.draw(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))
    }

    private fun faint(color: Color): Color = Color(color.red, color.green, color.blue, FAINT_ALPHA)

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LIST
            }
        }
        return accessibleContext
    }

    companion object {
        /** Width and height of one swatch cell, in unscaled pixels; leaves room for the focus ring. */
        const val CELL = 28
        const val CIRCLE = 16
        private const val RING_GAP = 2.75f
        private const val FOCUS_GAP = 4.75f
        private const val FAINT_ALPHA = 110
    }
}
