package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent

/**
 * A split whose tabs are all closed but which still shows pills (empty groups) has no tab for the
 * platform to draw a strip for. `JBTabsImpl.paintComponent` returns before `tabPainter.fillBackground` and `drawBorder` when there is no
 * visible tab, so the strip row had no background of its own (only each pill's own fill, [GroupHeader.stripBackground]) and no border line.
 * This paints both where the row is ([update]): the painter's strip background over the row, the line the platform's border draws above
 * the tabs (Top: `JBEditorTabsBorder`'s first line, through the border that is installed) and the line along the editor side of the row
 * (what `JBEditorTabsBorder.paintBorder` draws for every row of a normal strip), so the pills-only strip looks like a strip with tabs.
 *
 * A child of the tab component under the pills, laid out by [update] after every layout pass, invisible to the mouse. Internal APIs wrapped:
 * `JBTabsImpl.tabPainter` (public interface), `JBTabsImpl.tabBorder` (via [PlatformInternals.tabBorder]). EDT only.
 */
internal class StripBackdrop(private val tabs: JBEditorTabs) : JComponent() {
    /** The row itself, in tab component coordinates (the component is a little larger where a line lies outside it). */
    var row: Rectangle = Rectangle()
        private set

    init {
        isOpaque = false
        putClientProperty("Layout.done", true) // JBTabsImpl.LAYOUT_DONE: not reset to 0x0 by the platform's layout
    }

    override fun contains(x: Int, y: Int): Boolean = false

    /** Shows the backdrop over [strip] (the row, tab component coordinates) if [show], else hides it; keeps it at the bottom of the z-order. */
    fun update(show: Boolean, strip: Rectangle) {
        val position = tabs.tabsPosition
        val t = tabs.borderThickness
        val wanted = if (!show || strip.isEmpty) Rectangle() else when (position) {
            JBTabsPosition.top -> Rectangle(strip.x, strip.y, strip.width, strip.height + t) // the line under the row is outside it
            else -> Rectangle(strip)
        }
        row = if (wanted.isEmpty) Rectangle() else Rectangle(strip)
        if (bounds != wanted) {
            bounds = wanted
            repaint()
        }
        if (parent === tabs.component) {
            val last = tabs.componentCount - 1
            if (tabs.getComponentZOrder(this) != last) tabs.setComponentZOrder(this, last)
        }
    }

    override fun paintComponent(g: Graphics) {
        if (width <= 0 || height <= 0) return
        val g2 = g.create() as Graphics2D
        try {
            val painter = tabs.tabPainter
            // the row's background; the row starts at the component's origin except for nothing (Top / Bottom / Left / Right all start there)
            painter.fillBackground(tabs, g2, Rectangle(0, 0, row.width, row.height))
            g2.translate(-x, -y)
            // the line above the tabs (Top: inside the row) as the platform's border paints it first
            PlatformInternals.tabBorder(tabs)?.paintBorder(tabs, g2, 0, 0, tabs.width, tabs.height)
            val t = tabs.borderThickness
            when (tabs.tabsPosition) {
                JBTabsPosition.top ->
                    if (tabs.shouldPaintBottomBorder()) painter.paintBorderLine(g2, t, Point(row.x, row.y + row.height), Point(row.x + row.width, row.y + row.height))
                JBTabsPosition.bottom -> painter.paintBorderLine(g2, t, Point(row.x, row.y), Point(row.x + row.width, row.y))
                JBTabsPosition.right -> painter.paintBorderLine(g2, t, Point(row.x, row.y), Point(row.x, row.y + row.height))
                JBTabsPosition.left -> {
                    val lx = row.x + row.width - t
                    painter.paintBorderLine(g2, t, Point(lx, row.y), Point(lx, row.y + row.height))
                }
            }
        }
        finally {
            g2.dispose()
        }
    }
}
