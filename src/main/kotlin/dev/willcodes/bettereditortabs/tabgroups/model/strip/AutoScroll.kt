package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Rectangle

/**
 * Pure rules for scrolling a tab strip while something is dragged over it (DRAG-0: dragging to the end of a strip that is longer than
 * the window). No platform types, unit tested; `DragAutoScroll` (in `tabbar/drag`) feeds it the live strip and runs the timer.
 *
 * Geometry (all along the strip's main axis, in the tab component's coordinates; the "visible span" is the part of the strip where
 * tabs can be seen, i.e. up to the more button or the actions toolbar at the end):
 * - the **edge zone** is a few dozen px wide at each end of the span; a pointer inside it (or beyond the end, over the more button or
 *   the toolbar, which are part of the strip's rectangle) scrolls towards that end;
 * - the speed grows with the depth into the zone, from [MIN_STEP_PX] at its inner border to [MAX_STEP_PX] at the edge and beyond;
 * - the zone is never wider than a third of the span, so the two zones of a short strip never meet.
 */
object AutoScroll {
    /** Width of the edge zone, unscaled px. */
    const val ZONE_PX = 36

    /** Scroll step per tick at the inner border of the zone, unscaled px. */
    const val MIN_STEP_PX = 2

    /** Scroll step per tick at the edge of the span, unscaled px. */
    const val MAX_STEP_PX = 26

    /** One tick, in milliseconds (about one frame). */
    const val TICK_MS = 16

    /**
     * The step for a pointer at [at] on a span [start, end): negative towards the start, positive towards the end, 0 outside the zones.
     * [zone], [minStep] and [maxStep] are already scaled to the screen.
     */
    fun step(at: Int, start: Int, end: Int, zone: Int, minStep: Int, maxStep: Int): Int {
        val length = end - start
        if (length <= 0 || zone <= 0) return 0
        val z = minOf(zone, length / 3).coerceAtLeast(1)
        val intoEnd = at - (end - z)
        if (intoEnd >= 0) return ramp(intoEnd + 1, z, minStep, maxStep)
        val intoStart = (start + z) - at
        if (intoStart > 0) return -ramp(intoStart, z, minStep, maxStep)
        return 0
    }

    private fun ramp(depth: Int, zone: Int, minStep: Int, maxStep: Int): Int {
        val d = depth.coerceIn(1, zone)
        return minStep + ((maxStep - minStep).toLong() * (d - 1) / maxOf(1, zone - 1)).toInt()
    }

    /**
     * The end of the visible span along the main axis: [base] (the component's length) or the start of the first of [buttons] (the
     * more button, the entry point toolbar) that lies in the band [bandStart, bandEnd) across the strip, whichever is smaller. Buttons
     * with an empty rectangle, or that start at the very beginning, are ignored.
     */
    fun spanEnd(base: Int, buttons: List<Rectangle>, bandStart: Int, bandEnd: Int, horizontal: Boolean): Int {
        var end = base
        for (b in buttons) {
            if (b.isEmpty) continue
            val cross0 = if (horizontal) b.y else b.x
            val cross1 = cross0 + if (horizontal) b.height else b.width
            if (cross0 >= bandEnd || cross1 <= bandStart) continue
            val start = if (horizontal) b.x else b.y
            if (start > 0) end = minOf(end, start)
        }
        return end
    }

    /**
     * The cross-axis band (start and end inclusive) of the row that scrolls, from the tab area [header] ([rowCount] rows of
     * [rowHeight]). A strip with a separate pinned row has two rows and only the unpinned one scrolls: below the pinned row at the top
     * of the window, above it at the bottom ([top]). Side strips have one column. Null if there is nothing to scroll (a wrapped strip).
     */
    fun scrollingBand(header: Rectangle, rowCount: Int, rowHeight: Int, horizontal: Boolean, top: Boolean): IntRange? {
        if (header.isEmpty) return null
        if (!horizontal) return header.x until header.x + header.width
        if (rowCount <= 1) return header.y until header.y + header.height
        if (rowHeight <= 0 || rowCount > 2) return null
        return if (top) header.y + header.height - rowHeight until header.y + header.height else header.y until header.y + rowHeight
    }

    /** Is a pointer at cross coordinate [at] close enough to [band] (within [tolerance] px) for the strip to scroll? */
    fun inBand(at: Int, band: IntRange, tolerance: Int): Boolean = at >= band.first - tolerance && at <= band.last + tolerance

    /**
     * How far a layout that does not limit the offset itself may move by [step] from [offset]: the offset stays within 0 and
     * [requiredLength] - [scrollExtent]. Returns the allowed step (possibly 0).
     */
    fun clampedStep(offset: Int, step: Int, requiredLength: Int, scrollExtent: Int): Int {
        val max = maxOf(0, requiredLength - scrollExtent)
        val target = (offset + step).coerceIn(0, max)
        return target - offset
    }
}
