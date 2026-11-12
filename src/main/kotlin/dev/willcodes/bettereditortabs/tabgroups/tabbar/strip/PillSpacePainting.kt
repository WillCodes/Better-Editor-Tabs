package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.JBTabPainter
import com.intellij.ui.tabs.JBTabsBorder
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.ui.GraphicsUtil
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Area

/**
 * Makes the space reserved for a pill inside a host tab label look like plain strip background.
 *
 * How the platform paints a tab, in order (all of it over the label's *whole* bounds, which include our reserved inset):
 * 1. `JBTabsImpl.paintComponent`: strip background, then `drawBorder` (row separator lines and, for the classic painters, the
 *    selected underline over `selectedLabel.bounds`);
 * 2. per label, `TabLabel.paintComponent` -> `TabPainterAdapter.paintBackground` (tab fill, tint, selected / hover state;
 *    Islands: a rounded rectangle from x = 0 to the content's right edge);
 * 3. per label, the label border ([ReservedSpaceBorder.paintBorder], ours), then the label content;
 * 4. our pills, which are siblings above the labels;
 * 5. `EditorTabs.paintChildren` ends with `drawBorder` again (the underline once more).
 *
 * Step 3 clears the reserved area back to the strip background ([clearReserved]); for the rounded Islands tab the whole tab is
 * cleared and painted again in the rectangle that is left, so its rounded corner sits at the edge of the reserved area.
 * Step 5 is cut off from the reserved areas by [PillAwareTabsBorder].
 *
 * Wraps: `JBTabsImpl.tabPainter` (`JBTabPainter`, public interface), `TabLabel.info`, `JBTabsImpl.isHoveredOrWithPopup`.
 */
internal object PillSpacePainting {
    /** Paints the strip background over the reserved [leading] / [trailing] areas of [label] (label coordinates). */
    fun clearReserved(tabs: JBTabsImpl, label: TabLabel, g: Graphics, leading: Int, trailing: Int) {
        val w = label.width
        val h = label.height
        if (w <= 0 || h <= 0) return
        val g2 = g.create() as Graphics2D
        try {
            val painter = tabs.tabPainter
            if (isRounded(painter)) {
                // Islands: the tab is a rounded rectangle. Clear it and paint it again in the part that belongs to the tab.
                painter.fillBackground(tabs, g2, Rectangle(0, 0, w, h))
                val rest = Rectangle(leading, 0, w - leading - trailing, h)
                if (rest.width <= 0) return
                GraphicsUtil.setupAAPainting(g2)
                val info = label.info
                val active = tabs.isActiveTabs(info)
                val hovered = tabs.isHoveredOrWithPopup(label)
                if (info == tabs.selectedInfo) {
                    painter.paintSelectedTab(tabs.tabsPosition, g2, rest, tabs.borderThickness, info.tabColor, active, hovered)
                }
                else {
                    painter.paintTab(tabs.tabsPosition, g2, rest, tabs.borderThickness, info.tabColor, active, hovered)
                }
            }
            else {
                if (leading > 0) painter.fillBackground(tabs, g2, Rectangle(0, 0, minOf(leading, w), h))
                if (trailing > 0) painter.fillBackground(tabs, g2, Rectangle(maxOf(0, w - trailing), 0, minOf(trailing, w), h))
            }
        }
        finally {
            g2.dispose()
        }
    }

    /** Islands paints a tab as a rounded rectangle inset from the label (see [PlatformInternals.isIslandsPainter]). */
    fun isRounded(painter: JBTabPainter): Boolean = PlatformInternals.isIslandsPainter(painter)
}

/**
 * A pill's slot (tab-bar coordinates, inside its host label). [band] is the part of the slot that lies on the strip's border line
 * (the line between the tabs and the editor, `borderThickness` thick); [selectedHost] tells that the host is the selected tab,
 * whose underline reaches into the slot.
 */
internal class PillCut(val slot: Rectangle, val band: Rectangle, val selectedHost: Boolean)

/**
 * Wraps the tab bar's own `JBTabsBorder` (which paints the row lines and the selected underline, also after the children, on top
 * of our pills) and cuts the pills' slots out of its clip. The slot then stays plain strip.
 *
 * The strip's border line runs along the edge of the labels, through the slots. It is kept (the slot minus its [PillCut.band] is
 * cut out), except under the selected host, whose underline overlaps the line: there the whole slot is cut out and the line
 * is painted again by hand ([JBTabPainter.paintBorderLine], what `JBEditorTabsBorder` itself uses).
 *
 * Installed by [installOn] through [PlatformInternals.setTabBorder] (`JBTabsImpl.tabBorder` is private final: `drawBorder` reads that
 * field and the class is not open). If the field is not there (platform change) nothing is installed and only the underline of a
 * selected host shows under its pill; nothing else depends on it.
 *
 * It also draws the strip line along the row a separate pinned row leaves for loose pills ([SpareRow], [spareRow]): the platform
 * draws the lines of its own rows only.
 */
internal class PillAwareTabsBorder(
    private val bar: JBTabsImpl,
    val delegate: JBTabsBorder,
    private val cuts: () -> List<PillCut>,
    private val spareRow: () -> Rectangle? = { null },
) : JBTabsBorder(bar) {
    override val effectiveBorder: Insets get() = delegate.effectiveBorder

    override fun getBorderInsets(c: Component?): Insets = delegate.getBorderInsets(c)

    override fun isBorderOpaque(): Boolean = delegate.isBorderOpaque

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        paintCut(c, g, x, y, width, height)
        paintSpareRowLine(g, x, width)
    }

    /**
     * The line of the spare row ([SpareRow]), drawn like the platform's row lines (`JBEditorTabsBorder.paintBorder`). Top: the platform
     * draws the line under the pinned row only when the strip has a bottom line, and then it separates the two rows; the spare row gets
     * the bottom line under it. Without a bottom line the separator between the rows is drawn instead. Bottom: every row has its line
     * on top, so the spare row (above the pinned row) gets one too.
     */
    private fun paintSpareRowLine(g: Graphics, x: Int, width: Int) {
        val row = spareRow() ?: return
        if (bar.isHideTabs || bar.isEmptyVisible) return
        val t = bar.borderThickness
        val lineY = when (bar.tabsPosition) {
            JBTabsPosition.top -> {
                val bottomLine = (bar as? JBEditorTabs)?.shouldPaintBottomBorder() != false
                if (bottomLine) row.y + row.height - t else row.y - t
            }
            JBTabsPosition.bottom -> row.y
            else -> return
        }
        bar.tabPainter.paintBorderLine(g as Graphics2D, t, Point(x, lineY), Point(x + width, lineY))
    }

    private fun paintCut(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val all = cuts().filter { !it.slot.isEmpty }
        if (all.isEmpty()) {
            delegate.paintBorder(c, g, x, y, width, height)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            val area = Area(g2.clip ?: Rectangle(x, y, width, height))
            for (cut in all) {
                val part = Area(cut.slot)
                if (!cut.selectedHost && !cut.band.isEmpty) part.subtract(Area(cut.band))
                area.subtract(part)
            }
            g2.clip = area
            delegate.paintBorder(c, g2, x, y, width, height)
        }
        finally {
            g2.dispose()
        }
        val lineShown = !(bar.tabsPosition == JBTabsPosition.top && (bar as? JBEditorTabs)?.shouldPaintBottomBorder() == false)
        if (!lineShown || bar.isEmptyVisible || bar.isHideTabs) return
        val vertical = bar.tabsPosition.isSide
        for (cut in all) {
            if (!cut.selectedHost || cut.band.isEmpty) continue
            val g3 = g.create() as Graphics2D
            try {
                g3.clipRect(cut.band.x, cut.band.y, cut.band.width, cut.band.height)
                val from = Point(cut.band.x, cut.band.y)
                val to = if (vertical) Point(cut.band.x, cut.band.y + cut.band.height) else Point(cut.band.x + cut.band.width, cut.band.y)
                bar.tabPainter.paintBorderLine(g3, bar.borderThickness, from, to)
            }
            finally {
                g3.dispose()
            }
        }
    }

    companion object {
        /** Installs the wrapper on [tabs]; returns it, or null if that is not possible. */
        fun installOn(tabs: JBTabsImpl, cuts: () -> List<PillCut>, spareRow: () -> Rectangle? = { null }): PillAwareTabsBorder? {
            val current = PlatformInternals.tabBorder(tabs) ?: return null
            if (current is PillAwareTabsBorder) return current
            return PillAwareTabsBorder(tabs, current, cuts, spareRow).also { PlatformInternals.setTabBorder(tabs, it) }
        }

        fun uninstallFrom(tabs: JBTabsImpl) {
            val current = PlatformInternals.tabBorder(tabs) as? PillAwareTabsBorder ?: return
            PlatformInternals.setTabBorder(tabs, current.delegate)
        }
    }
}
