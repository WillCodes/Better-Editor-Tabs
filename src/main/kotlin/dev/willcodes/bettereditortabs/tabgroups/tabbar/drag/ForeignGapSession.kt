package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropSlots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabCell
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.ForeignAnchor
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.Point
import java.awt.Rectangle

/**
 * The size of a group that is being dragged, as far as another strip needs it to open room for the group.
 *
 * [length] and [pill] are what the group takes along its own strip (its first row, and the pill-sized part under the pointer);
 * they are right for any strip of the same orientation ([horizontal]). A strip of the other orientation measures different
 * things (a width here is a number of rows there), so the natural top / bottom lengths ([pillNatural], [tabsNatural]) and the
 * number of tabs ([tabCount]) are kept as well, see [ForeignGapSession.lengthsIn].
 */
internal class DraggedGroupSize(
    val horizontal: Boolean,
    val length: Int,
    val pill: Int,
    val collapsed: Boolean,
    val pillNatural: Int,
    val tabsNatural: Int,
    val tabCount: Int,
)

/**
 * The gap that a group drag opens in *another* split's strip (INT-6): the same kind of gap as in the group's own strip, of the size
 * the group will have there, opening between that strip's units and following the pointer by the same rule ([DropSlots.select]).
 * Owned by [HeaderDragHelper] for the time of one drag; at most one strip has the gap open.
 *
 * Opening the gap can add a row to a wrapped strip, so that strip's rectangle grows and shrinks because of the drag. Like the own
 * strip, "is the pointer still over it" is judged against the widest that strip has been while the gap was open ([claims]).
 *
 * Wraps [TabBarAdapter.setForeignGap] / `foreignGapRect` of the other split. EDT only.
 */
internal class ForeignGapSession {
    /** The strip's units as laid out with the gap open, and the boundary (unit index) the gap is at. */
    class Opened(val units: List<MoveUnit<VirtualFile>>, val boundary: Int)

    private var host: TabBarDnd? = null
    private var boundary = -1
    private var reach: Rectangle? = null

    /** The split whose strip has the gap open, if the pointer at [screen] is still over it or just beside its rows. */
    fun claims(screen: Point): TabBarDnd? {
        val h = host ?: return null
        val r = reach ?: return null
        return h.takeIf { StripDragGeometry.beside(r, h.horizontal, JBUI.scale(STICKY_MARGIN_PX)).contains(screen) }
    }

    /** True if the gap is open in a strip other than [other] (it has to be closed before another one opens). */
    fun isElsewhere(other: TabBarDnd?): Boolean = host != null && host !== other

    /** Opens the gap in [other] (or keeps it) and moves it to where the pointer at [screen] is, until its slot settles. */
    fun settle(other: TabBarDnd, screen: Point, size: DraggedGroupSize): Opened {
        val p = other.toTabs(screen)
        var units = other.buildUnits()
        val low = DropRules.leadingPinned(units)
        val (room, pill) = lengthsIn(other, size)
        if (host !== other) {
            close()
            host = other
            boundary = DropRules.nearestBoundary(units.map { it.bounds }, other.horizontal, p, low)
            units = open(other, units, room)
        }
        repeat(SETTLE_STEPS) {
            val gap = other.adapter.foreignGapRect() ?: return@repeat
            val probe = if (other.horizontal) Rectangle(gap.x, gap.y, minOf(pill, gap.width), gap.height)
            else Rectangle(gap.x, gap.y, gap.width, minOf(pill, gap.height))
            val cells = units.mapIndexed { i, u -> TabCell(i, u.groupId, u.pinned, Rectangle(u.bounds)) as StripCell<Int> }
            val slot = DropSlots.select(
                cells, probe, boundary, p, other.horizontal, low.coerceAtMost(cells.size), cells.size,
                if (other.horizontal) gap.width else gap.height,
            )
            if (slot == boundary) return Opened(units, boundary)
            boundary = slot
            units = open(other, units, room)
        }
        return Opened(units, boundary)
    }

    /** Closes the gap, if one is open. */
    fun close() {
        host?.adapter?.setForeignGap(null, 0)
        host = null
        reach = null
        boundary = -1
    }

    /** Opens the gap in [other] at [boundary]; returns the strip's units as they are laid out then. */
    private fun open(other: TabBarDnd, units: List<MoveUnit<VirtualFile>>, room: Int): List<MoveUnit<VirtualFile>> {
        val at = units.getOrNull(boundary)
        val anchor = when {
            at == null -> ForeignAnchor.AtEnd
            at.collapsed -> ForeignAnchor.BeforeCollapsedGroup(at.groupId ?: return units)
            else -> ForeignAnchor.BeforeTab(at.keys.firstOrNull() ?: return units)
        }
        other.adapter.setForeignGap(anchor, room)
        if (!other.tabs.isValid) other.tabs.validate()
        other.stripScreenRect()?.let { reach = reach?.union(it) ?: Rectangle(it) }
        return other.buildUnits()
    }

    companion object {
        /** How far beside its rows (across the strip) the pointer still counts as on a strip during a group drag. */
        const val STICKY_MARGIN_PX = 24

        private const val SETTLE_STEPS = 4

        /**
         * The room the group needs along [other]'s strip, and the length of its pill-sized leading part there. In a strip of the
         * same orientation as its own that is what it takes there too. Across orientations the lengths are different things, so
         * they are worked out for the target: rows of the target's row height in a side strip (one for the pill, one per tab unless
         * the group is collapsed), natural widths in a top / bottom strip.
         */
        fun lengthsIn(other: TabBarDnd, size: DraggedGroupSize): Pair<Int, Int> {
            if (other.horizontal == size.horizontal) return size.length to size.pill
            if (other.horizontal) {
                val pill = size.pillNatural.coerceAtLeast(1)
                return (pill + if (size.collapsed) 0 else size.tabsNatural) to pill
            }
            val row = other.rowLength().takeIf { it > 0 } ?: size.pill
            return row * (1 + if (size.collapsed) 0 else size.tabCount) to row
        }
    }
}
