package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ide.ui.UISettings
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GroupLines
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D

/**
 * LINE-6 (Islands, user request 2026-11-14): a line in the group colour under each expanded (or partially expanded, COLL-8) group's
 * island tabs, from its header to its last tab, in addition to the tint (LINE-1). Only where the strip's painter is the Islands one
 * ([PlatformInternals.isIslandsPainter]); other New UI themes keep tint and underline only. Where the line runs: [GroupLines].
 *
 * Painted from the strip's border wrapper after the platform's post-children border layer ([PillAwareTabsBorder], like the POP-4
 * outline), so no tab label or selected island paints over it. The lines are recomputed after every layout pass ([refresh]: the
 * collapse animation, drags and scrolling move the labels), opaque, with rounded ends. Island metrics as `IslandsTabPainter.paintTab`
 * (262 source): along-the-strip inset 4 px (2 compact, 6 in a side strip), margin across the strip half of
 * `max(labelHeight - fullHeight, minVOffset)` with fullHeight 28 (24 compact) and minVOffset 8 (4 compact, 6 in a side strip). EDT only.
 */
internal class GroupLine(private val tabs: JBEditorTabs) {
    /** The lines shown now, with their colours; tab component coordinates. */
    private var lines: List<Pair<Rectangle, Color>> = emptyList()

    /** The rectangles drawn now (tests). */
    fun shown(): List<Rectangle> = lines.map { Rectangle(it.first) }

    /** True if the strip is painted by the Islands painter (the only theme with island tabs). */
    fun isIslands(): Boolean = PlatformInternals.isIslandsPainter(tabs.tabPainter)

    /**
     * Re-places the lines after a layout pass. [groups] gives, for each group to underline, its colour and its visible units in strip
     * order (header pill, tabs, a trailing count); read only in Islands with the strip shown.
     */
    fun refresh(stripShown: Boolean, groups: () -> List<Pair<Color, List<GroupLines.Unit>>>) {
        val now = if (!stripShown || !isIslands()) emptyList() else compute(groups())
        if (now == lines) return
        val dirty = (lines + now).map { it.first }
        lines = now
        for (r in dirty) tabs.component.repaint(r.x - 1, r.y - 1, r.width + 2, r.height + 2)
    }

    private fun compute(groups: List<Pair<Color, List<GroupLines.Unit>>>): List<Pair<Rectangle, Color>> {
        if (groups.isEmpty()) return emptyList()
        val side = when (tabs.tabsPosition) {
            JBTabsPosition.bottom -> GroupLines.Side.BOTTOM
            JBTabsPosition.left -> GroupLines.Side.LEFT
            JBTabsPosition.right -> GroupLines.Side.RIGHT
            else -> GroupLines.Side.TOP
        }
        val metrics = metrics(side == GroupLines.Side.LEFT || side == GroupLines.Side.RIGHT)
        return groups.flatMap { (color, units) -> GroupLines.segments(units, side, metrics).map { it to color } }
    }

    /** The Islands island metrics for this strip (`IslandsTabPainter.paintTab`, `getHOffsetUnscaled`). */
    private fun metrics(sideStrip: Boolean): GroupLines.Metrics {
        val compact = UISettings.getInstance().compactMode
        val inset = JBUIScale.scale(if (sideStrip) 6 else if (compact) 2 else 4)
        val minV = JBUIScale.scale(if (compact) 4 else if (sideStrip) 6 else 8)
        val full = JBUIScale.scale(if (compact) 24 else 28)
        return GroupLines.Metrics(inset, { h -> (h - full).coerceAtLeast(minV) / 2 }, JBUIScale.scale(2))
    }

    /** Called by the border wrapper at the end of each border pass, in tab component coordinates. */
    fun paint(g: Graphics) {
        if (lines.isEmpty() || tabs.isHideTabs || tabs.isEmptyVisible) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for ((r, color) in lines) {
                g2.color = color
                val arc = minOf(r.width, r.height).toFloat()
                g2.fill(RoundRectangle2D.Float(r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat(), arc, arc))
            }
        }
        finally {
            g2.dispose()
        }
    }
}
