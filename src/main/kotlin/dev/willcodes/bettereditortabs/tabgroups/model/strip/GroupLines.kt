package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Rectangle

/**
 * Islands theme: where the line in the group colour runs under a group's island tabs, from its header to
 * its last tab. Pure geometry; the painter is `tabbar/strip/GroupLine`.
 *
 * The Islands theme draws each tab as a rounded "island" inset in its label: by [Metrics.tabInset] along the strip, and centred across
 * it with a margin of [Metrics.margin] on both sides (`IslandsTabPainter.paintTab`). The line runs in that margin on the side facing the
 * editor (below the tabs for Top, above them for Bottom, right of them for Left, left of them for Right: where Chrome draws its group
 * line, between the tabs and the page), centred in it, so it never touches an island or its selected outline.
 *
 * Along the strip it spans the group's units on each row (one bar per row in multi-row mode): from the header pill's start (or the row's
 * first tab island) to the end of the last unit (a tab island, or a trailing unit such as the "+N" count of a partial expand).
 * A group without a visible tab has no line (collapsed or empty), and a row with only the group's pill on it (the header
 * wrapped to the end of a row) has none either.
 */
object GroupLines {
    enum class Side { TOP, BOTTOM, LEFT, RIGHT }

    /** One visible element of the group in the strip: a tab label's own area ([isTab]) or a pill / count (drawn as is). */
    data class Unit(val rect: Rectangle, val isTab: Boolean)

    /**
     * @param tabInset the island's inset from its label along the strip (`IslandsTabPainter.getHOffsetUnscaled`, scaled).
     * @param margin the island's margin across the strip for a label of that thickness (half of `IslandsTabPainter`'s `vOffset`).
     * @param thickness the line's thickness.
     */
    class Metrics(val tabInset: Int, val margin: (labelThickness: Int) -> Int, val thickness: Int)

    /** The line's rectangles for one group whose visible [units] are given in strip order; empty if the group has no visible tab. */
    fun segments(units: List<Unit>, side: Side, metrics: Metrics): List<Rectangle> {
        val visible = units.filter { !it.rect.isEmpty }
        if (visible.none { it.isTab }) return emptyList()
        return if (side == Side.LEFT || side == Side.RIGHT) vertical(visible, side, metrics) else horizontal(visible, side, metrics)
    }

    private fun horizontal(units: List<Unit>, side: Side, m: Metrics): List<Rectangle> {
        val result = ArrayList<Rectangle>()
        for (row in rows(units)) {
            val tabs = row.filter { it.isTab }
            if (tabs.isEmpty()) continue
            val start = row.minOf { if (it.isTab) it.rect.x + m.tabInset else it.rect.x }
            val end = row.maxOf { if (it.isTab) it.rect.x + it.rect.width - m.tabInset else it.rect.x + it.rect.width }
            if (end <= start) continue
            val top = tabs.minOf { it.rect.y }
            val bottom = tabs.maxOf { it.rect.y + it.rect.height }
            val margin = m.margin(bottom - top)
            val inMargin = ((margin - m.thickness) / 2).coerceAtLeast(0)
            val y = if (side == Side.TOP) bottom - margin + inMargin else top + inMargin
            result += Rectangle(start, y, end - start, m.thickness)
        }
        return result
    }

    private fun vertical(units: List<Unit>, side: Side, m: Metrics): List<Rectangle> {
        val tabs = units.filter { it.isTab }
        val left = tabs.minOf { it.rect.x }
        val right = tabs.maxOf { it.rect.x + it.rect.width }
        val margin = m.margin(tabs.maxOf { it.rect.height })
        val start = units.minOf { if (it.isTab) it.rect.y + margin else it.rect.y }
        val end = units.maxOf { if (it.isTab) it.rect.y + it.rect.height - margin else it.rect.y + it.rect.height }
        if (end <= start) return emptyList()
        // across a side strip the island's inset is the along-the-strip inset of a horizontal one (`getHOffsetUnscaled(isSide)`)
        val inMargin = ((m.tabInset - m.thickness) / 2).coerceAtLeast(0)
        val x = if (side == Side.LEFT) right - m.tabInset + inMargin else left + inMargin
        return listOf(Rectangle(x, start, m.thickness, end - start))
    }

    /** [units] grouped into rows: units whose vertical ranges overlap are on one row; rows in the order of their first unit. */
    private fun rows(units: List<Unit>): List<List<Unit>> {
        val rows = ArrayList<MutableList<Unit>>()
        for (u in units) {
            val row = rows.firstOrNull { r -> r.any { overlaps(it.rect, u.rect) } }
            if (row != null) row += u else rows += mutableListOf(u)
        }
        return rows
    }

    private fun overlaps(a: Rectangle, b: Rectangle): Boolean {
        val ac = a.y + a.height / 2
        val bc = b.y + b.height / 2
        return ac in b.y until b.y + b.height || bc in a.y until a.y + a.height
    }
}
