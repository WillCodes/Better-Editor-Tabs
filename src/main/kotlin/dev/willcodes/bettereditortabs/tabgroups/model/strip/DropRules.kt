package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Point
import java.awt.Rectangle

/**
 * Pure drag-and-drop decisions (no platform types, unit tested): which group a dropped tab joins (DRAG-1..DRAG-6) and where a
 * dragged group may be dropped (INT-6).
 *
 * Coordinates are along the tab strip's main axis (x for Top/Bottom, y for Left/Right) and in one coordinate space.
 */
object DropRules {
    // ---- tab drops (DRAG-1..DRAG-7) ------------------------------------------------------------------------------------
    //
    // The strip is a sequence of cells: ungrouped tabs, group member tabs and group pills. A collapsed group is a single pill cell
    // (its hidden members are not cells). The dragged tab is not part of the sequence; the question is which of the gaps ("slots",
    // 0..cells.size) it is dropped into and which group it belongs to there.

    /**
     * Decides membership and final position for a tab dropped into [requestedSlot]:
     * - between a group's pill and its first tab, or between two of its tabs: joins at once (DRAG-1, DRAG-2);
     * - right behind its last tab (trailing slot), or right behind the pill of an empty group (a [PillCell.empty] is that group's whole
     *   body: its trailing slot): outside, unless [joiningGroup] is that group (hold-to-join, DRAG-3); the slot in front of the pill is outside;
     * - before a pill: outside (DRAG-6, the collapsed group is one unit); right behind a collapsed pill (a partly shown group's unit
     *   included, COLL-8) is the group's trailing slot like behind an empty group's pill: outside, unless [joiningGroup] is that
     *   group (the hold completed there, which makes the drag expand the group, so that the slots inside it apply);
     * - a pinned tab never joins (DRAG-4) and, if it was dropped inside a group, goes in front of the pill; with
     *   [keepPinnedFirst] pinned tabs stay in the leading pinned zone and other tabs stay out of it (INV-3).
     */
    fun <T> landing(
        cells: List<StripCell<T>>,
        requestedSlot: Int,
        pinned: Boolean,
        keepPinnedFirst: Boolean,
        joiningGroup: String?,
    ): Landing {
        var slot = requestedSlot.coerceIn(0, cells.size)
        if (keepPinnedFirst) {
            val leading = cells.takeWhile { it is TabCell && it.pinned }.size
            slot = if (pinned) minOf(slot, leading) else maxOf(slot, leading)
        }
        val prev = cells.getOrNull(slot - 1)
        val next = cells.getOrNull(slot)
        val inside: String? = when {
            next is TabCell && next.groupId != null && prev is TabCell && prev.groupId == next.groupId -> next.groupId
            next is TabCell && next.groupId != null && prev is PillCell && prev.groupId == next.groupId && !prev.collapsed -> next.groupId
            else -> null
        }
        // a pill with no tab behind it: the dragged tab was the group's only tab. Right behind that pill is the group's trailing slot
        // (fix round 2, bug 9): the tab is a member there while it counts as one ([joiningGroup], see OwnGroupStay), so that dropping it
        // back changes nothing, and holding it there takes it out (DRAG-3, DRAG-8); without that it is outside, like any trailing slot
        val lonePill: String? = if (prev is PillCell && !prev.collapsed && !prev.empty && !(next is TabCell && next.groupId == prev.groupId)) prev.groupId else null
        val trailing: String? = when {
            lonePill != null && inside == null -> lonePill
            prev is TabCell && prev.groupId != null && inside == null -> prev.groupId
            // an empty group (its pill alone) has a trailing edge right behind its pill: held there, the tab joins (DRAG-3)
            prev is PillCell && prev.empty && inside == null -> prev.groupId
            // COLL-8: so has a collapsed group (full or partly shown): held there, the tab joins it and the group expands for the drag
            prev is PillCell && prev.collapsed && inside == null -> prev.groupId
            else -> null
        }

        if (pinned) {
            // a pinned tab dropped right behind the pill of the group it was the only tab of still goes in front of the pill
            val within = inside ?: lonePill
            if (within == null) return Landing(slot, null, null)
            val pill = cells.indexOfFirst { it is PillCell && it.groupId == within }
            val first = cells.indexOfFirst { it is TabCell && it.groupId == within }
            return Landing(if (pill >= 0) pill else maxOf(first, 0), null, null)
        }
        val group = inside ?: trailing?.takeIf { it == joiningGroup }
        return Landing(slot, group, trailing)
    }

    /**
     * The tab order (files) after dropping [dragged] with [landing]: the cells in order with [dragged] inserted at the slot. A
     * collapsed pill stands for its hidden tabs, which [hiddenMembers] supplies. Expanded pills contribute nothing (their tabs are cells).
     */
    fun <T> flatOrder(cells: List<StripCell<T>>, landing: Landing, dragged: T, hiddenMembers: (String) -> List<T>): List<T> =
        sequenceOrder(cells, landing, dragged, hiddenMembers).files()

    /**
     * [flatOrder] with the pills of empty groups kept in it (RULE-6): a collapsed pill whose [hiddenMembers] are none is an
     * [StripItem.EmptyPill] at its place, so the caller can tell where it stands relative to the dropped tab ([EmptyPillSpots.derive]).
     */
    fun <T> sequenceOrder(cells: List<StripCell<T>>, landing: Landing, dragged: T, hiddenMembers: (String) -> List<T>): List<StripItem<T>> {
        val out = ArrayList<StripItem<T>>()
        for (i in 0..cells.size) {
            if (i == landing.slot) out += StripItem.Tab(dragged)
            val c = cells.getOrNull(i) ?: continue
            when (c) {
                is TabCell -> out += StripItem.Tab(c.key)
                is PillCell -> if (c.collapsed) {
                    val members = hiddenMembers(c.groupId).filter { it != dragged }
                    if (members.isEmpty()) out += StripItem.EmptyPill(c.groupId) else members.mapTo(out) { StripItem.Tab(it) }
                }
            }
        }
        return out
    }

    // ---- group moves (INT-6) -----------------------------------------------------------------------------------------

    /**
     * The boundary (index [minBoundary]..units.size, "before unit i") nearest to [pointer]. Boundaries lie only between units,
     * never inside a group. [units] must be in display order. Boundary i is the leading edge of unit i; the last boundary is the
     * trailing edge of the last unit.
     */
    fun nearestBoundary(units: List<Rectangle>, horizontal: Boolean, pointer: Point, minBoundary: Int = 0): Int {
        if (units.isEmpty()) return 0
        var best = minBoundary.coerceIn(0, units.size)
        var bestDistance = Double.MAX_VALUE
        for (b in best..units.size) {
            // a unit scrolled out of the strip has an empty rectangle: there is nothing on screen to snap to
            if (units[minOf(b, units.size - 1)].isEmpty) continue
            val p = boundaryPoint(units, horizontal, b)
            val d = p.distanceSq(pointer)
            if (d < bestDistance) {
                bestDistance = d
                best = b
            }
        }
        return best
    }

    /** The point that represents boundary [b] (see [nearestBoundary]). */
    fun boundaryPoint(units: List<Rectangle>, horizontal: Boolean, b: Int): Point {
        if (b >= units.size) {
            val r = units.last()
            return if (horizontal) Point(r.x + r.width, r.y + r.height / 2) else Point(r.x + r.width / 2, r.y + r.height)
        }
        val r = units[b]
        return if (horizontal) Point(r.x, r.y + r.height / 2) else Point(r.x + r.width / 2, r.y)
    }

    /** Number of leading pinned units: group drops are not allowed before them (INV-3, pinned tabs stay at the start). */
    fun <T> leadingPinned(units: List<MoveUnit<T>>): Int = units.takeWhile { it.pinned }.size
}
