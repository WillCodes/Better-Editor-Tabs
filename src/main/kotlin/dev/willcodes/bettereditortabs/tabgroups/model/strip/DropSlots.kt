package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Point
import java.awt.Rectangle

/**
 * Pure, stateless choice of the drop slot during a tab (or group) drag No platform types, unit tested.
 *
 * The strip is live: the dragged element sits in the layout as a placeholder gap ([gap]) at the current slot and the strip slides
 * around it as the slot changes. That is what makes a naive "slot under the pointer" rule oscillate: when the slot changes the
 * cells move (by the gap's width, or to another row), the pointer is then over a different cell, which proposes the old slot again.
 *
 * The rule here mirrors how the platform's `DragHelper` decides swaps (`DragHelper.java:209-284`: a neighbour only swaps once the
 * dragged label has moved far enough that it would overlap its new place), extended to units of different widths:
 * - the pointer inside the gap means no change (it is where the placeholder is);
 * - a swap across the next cell U (forwards) happens only once the pointer is where the gap would be after the swap, i.e. at or
 *   beyond U's trailing edge as it will be once U has moved into the gap: `U.end - gap width` while the gap is still in front of U
 *   (backwards: before `U.start + gap width`);
 * - several cells are crossed one after the other, each time against the same live rectangles (the cells beyond the gap do not move
 *   when the gap moves across the cells in front of them, so the rectangles stay valid).
 *
 * After a swap the pointer is inside the new gap, so the next evaluation changes nothing (stable for a still pointer), and a pointer
 * moving steadily in one direction only ever crosses cells in that direction (monotonic): a backwards swap needs the pointer to be
 * before a cell's start plus the gap width, which a pointer that only moved forwards never is, because it left that region through
 * the gap's near edge.
 *
 * Group drags: the placeholder is then the pill-sized leading part of the group ([select]'s `gap`, the part under the pointer) while the
 * group takes more room in the layout (`shift`); the hysteresis uses the first, the shifted neighbours the second. Without that a wide
 * group would cross every neighbour the moment the pointer leaves it.
 *
 * Lines: cells are in reading order. In a wrapped strip (Top / Bottom, several rows) a cell in an earlier row than the pointer's row
 * is before the pointer, a cell in a later row is after it; within the pointer's row the rule above applies along the x axis. In a
 * Left / Right strip every tab and every pill is a line of its own and the strip is judged along y alone.
 * In a single row the cross axis is ignored altogether, so a pointer that drifts a few pixels above or below the strip does not
 * change the slot.
 */
object DropSlots {
    /**
     * The slot (0..cells.size, "before cell i") for [pointer].
     *
     * @param cells the strip without the dragged element, in reading order, with the live rectangles (tab-bar coordinates)
     * @param gap the live rectangle of the dragged element (the placeholder), or null if it is not laid out
     * @param current the slot the gap sits in now
     * @param lo,hi the slots that may be chosen (pinned tabs stay in the pinned zone, others out of it)
     * @param shift the room (along the strip) the dragged element takes in the live layout when [gap] is only the part of it that the
     *   pointer stands for (group drag); null = the gap's own length
     */
    fun <T> select(
        cells: List<StripCell<T>>,
        gap: Rectangle?,
        current: Int,
        pointer: Point,
        horizontal: Boolean,
        lo: Int = 0,
        hi: Int = cells.size,
        shift: Int? = null,
    ): Int {
        val n = cells.size
        val low = lo.coerceIn(0, n)
        val high = hi.coerceIn(low, n)
        val cur = current.coerceIn(low, high)
        val liveGap = gap?.takeIf { !it.isEmpty }

        fun start(r: Rectangle) = if (horizontal) r.x else r.y
        fun end(r: Rectangle) = if (horizontal) r.x + r.width else r.y + r.height
        val at = if (horizontal) pointer.x else pointer.y
        // the part of the gap the pointer stands for, and the room the dragged element takes in the live layout
        val probe = liveGap?.let { end(it) - start(it) } ?: 0
        val room = shift ?: probe

        val ordered = ArrayList<Rectangle>(n + 1)
        for (i in 0..n) {
            if (i == cur && liveGap != null) ordered += liveGap
            if (i < n) ordered += cells[i].rect
        }
        // Rows only exist in a wrapped horizontal strip; a side strip is one cell per line and is judged along y alone.
        val bands = if (horizontal) rowBands(ordered) else emptyList()
        val rows = bands.size > 1
        fun lineOf(r: Rectangle): Int = if (rows) rowIndex(bands, r) else 0
        val pLine = if (rows) pointerRow(bands, pointer.y) else 0
        val gapLine = liveGap?.let { lineOf(it) } ?: pLine

        if (liveGap != null && at >= start(liveGap) && at < end(liveGap) && (!rows || gapLine == pLine)) return cur

        /** Is cell [u], behind the gap, passed by the pointer? Rectangles are live: cells behind the gap are shifted by its room. */
        fun passedForward(u: Rectangle): Boolean {
            val l = lineOf(u)
            return when {
                rows && l < pLine -> true
                rows && l > pLine -> false
                else -> at >= end(u) - (if (liveGap != null) room else 0)
            }
        }

        /** Is cell [u], in front of the gap, passed by the pointer moving back? */
        fun passedBackward(u: Rectangle): Boolean {
            val l = lineOf(u)
            return when {
                rows && l > pLine -> true
                rows && l < pLine -> false
                else -> at < start(u) + probe
            }
        }

        var s = cur
        while (s < high) {
            val u = cells[s].rect
            if (u.isEmpty) {
                // Cells scrolled out of the strip (a trailing pill behind the last tab, cut off by the overflow button) cannot be
                // judged by position: when nothing visible is left behind the gap and the pointer is past it, the end is reached.
                val restEmpty = (s until high).all { cells[it].rect.isEmpty }
                val pastGap = liveGap == null || (at >= start(liveGap) + probe && (!rows || pLine >= gapLine))
                if (restEmpty && pastGap) s = high
                break
            }
            if (!passedForward(u)) break
            s++
        }
        if (s != cur) return s
        while (s > low) {
            val u = cells[s - 1].rect
            if (u.isEmpty || !passedBackward(u)) break
            s--
        }
        return s
    }

    // ---- hold zone (DRAG-3) -----------------------------------------------------------------------------------------------

    /**
     * How close (unscaled px along the main axis, measured from the trailing edge of a group's last tab) the pointer must be for the
     * hold-to-join to start. The floating image is centred on the pointer, so with a tab-wide image the zone means "the dragged tab
     * overlaps the end of the last tab" (Chrome: the tab has to be on the group's edge, not merely somewhere behind the group).
     */
    const val HOLD_ZONE_PX = 32

    /** Extra distance (unscaled px) the pointer may drift once the hold has completed before the join is lost. */
    const val HOLD_RELEASE_SLACK_PX = DragHoldDefaults.JITTER_PX * 2

    /**
     * Is [pointer] close enough to the group's last tab [last] (its rectangle, tab-bar coordinates) to hold there?
     * Along the main axis: from the start of [last] to its trailing edge plus [zone] ([slack] more when [joined], so that the join
     * does not flicker at the border of the zone). In a multi-row strip the pointer also has to be in the row of [last].
     */
    fun nearLastTab(last: Rectangle, pointer: Point, horizontal: Boolean, zone: Int, slack: Int, joined: Boolean, multiRow: Boolean): Boolean {
        if (last.isEmpty) return false
        val p = if (horizontal) pointer.x else pointer.y
        val end = mainEnd(last, horizontal) + zone + (if (joined) slack else 0)
        if (p < mainStart(last, horizontal) || p > end) return false
        if (multiRow && horizontal && (pointer.y < last.y || pointer.y >= last.y + last.height)) return false
        return true
    }

    /** True if the rectangles of [cells] (and [gap]) form more than one row (horizontal strips only). */
    fun <T> isMultiRow(cells: List<StripCell<T>>, gap: Rectangle?, horizontal: Boolean): Boolean {
        if (!horizontal) return false
        val all = cells.map { it.rect } + listOfNotNull(gap)
        return rowBands(all).size > 1
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private fun mainStart(r: Rectangle, horizontal: Boolean) = if (horizontal) r.x else r.y
    private fun mainEnd(r: Rectangle, horizontal: Boolean) = if (horizontal) r.x + r.width else r.y + r.height

    /** Vertical extents (top, bottom exclusive) of the rows, from rectangles in reading order. Empty rectangles are ignored. */
    internal fun rowBands(rects: List<Rectangle>): List<IntArray> {
        val bands = ArrayList<IntArray>()
        for (r in rects) {
            if (r.isEmpty) continue
            val centre = r.y + r.height / 2
            val last = bands.lastOrNull()
            if (last == null || centre >= last[1]) bands += intArrayOf(r.y, r.y + r.height)
            else {
                last[0] = minOf(last[0], r.y)
                last[1] = maxOf(last[1], r.y + r.height)
            }
        }
        return bands
    }

    private fun rowIndex(bands: List<IntArray>, r: Rectangle): Int {
        val centre = r.y + r.height / 2
        for ((i, b) in bands.withIndex()) if (centre < b[1]) return i
        return bands.size - 1
    }

    /** The row the pointer is in: the band that contains [y], else the nearest one. */
    private fun pointerRow(bands: List<IntArray>, y: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for ((i, b) in bands.withIndex()) {
            val d = if (y < b[0]) b[0] - y else if (y >= b[1]) y - b[1] + 1 else 0
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }
}
