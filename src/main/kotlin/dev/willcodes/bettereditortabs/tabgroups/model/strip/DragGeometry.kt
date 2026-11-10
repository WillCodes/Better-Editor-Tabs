package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Point
import java.awt.Rectangle

/**
 * Pure geometry of the group-drag ghost (INT-6), unit tested. Everything is in the tab bar component's coordinates unless stated.
 *
 * The ghost is a picture of exactly the pill plus the group's showing tab labels (just the pill when collapsed).
 */
internal object HeaderDragGeometry {
    /**
     * The rectangle to take the picture of: the union of the pill and the member labels that are showing. Empty rectangles
     * (a pill that isn't shown, a hidden member) don't count. Null if nothing is showing.
     */
    fun groupRect(header: Rectangle?, memberLabels: List<Rectangle>): Rectangle? {
        var result: Rectangle? = null
        for (r in listOfNotNull(header) + memberLabels) {
            if (r.isEmpty) continue
            result = result?.union(r) ?: Rectangle(r)
        }
        return result
    }

    /** Where a piece (pill or label) is drawn inside the picture of [rect]. */
    fun inPicture(piece: Rectangle, rect: Rectangle): Rectangle = Rectangle(piece.x - rect.x, piece.y - rect.y, piece.width, piece.height)
}

/** Pure geometry of a tab drag inside the strip, unit tested. Coordinates are the tab bar component's. */
internal object StripDragGeometry {
    /**
     * The extent of the strip across its main axis (y for Top / Bottom, x for Left / Right) from the rectangles of its rows or
     * columns, empty ones ignored; null if there is none.
     */
    fun crossBand(rects: List<Rectangle>, horizontal: Boolean): IntRange? {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (r in rects) {
            if (r.isEmpty) continue
            val a = if (horizontal) r.y else r.x
            val b = if (horizontal) r.y + r.height else r.x + r.width
            lo = minOf(lo, a)
            hi = maxOf(hi, b)
        }
        return if (lo > hi) null else lo until hi
    }

    /**
     * The widest band seen so far: [seen] extended by [now]. A live re-order can change the number of rows of a wrapped strip (the
     * placeholder moves to another row, a row empties), so the strip's band shrinks and grows *because of* the drag. Judging "has
     * the pointer left the strip" against the live band makes the drag leave, the strip go back, the band grow and the drag come
     * back, over and over; judging it against the widest band the strip had during this drag cannot do that.
     */
    fun widen(seen: IntRange?, now: IntRange?): IntRange? = when {
        seen == null -> now
        now == null -> seen
        else -> minOf(seen.first, now.first)..maxOf(seen.last, now.last)
    }

    /**
     * [reach] (a strip's rectangle) grown by [margin] across the strip only: a pointer just beside the rows still belongs to the
     * strip, while moving along the strip past its ends does not get any extra room.
     */
    fun beside(reach: Rectangle, horizontal: Boolean, margin: Int): Rectangle =
        Rectangle(reach).also { if (horizontal) it.grow(0, margin) else it.grow(margin, 0) }

    /**
     * True when [pointer] is more than [margin] beyond [band] across the strip. Only the cross axis counts, like the platform: moving
     * along the strip never detaches a tab. Null band: never outside.
     */
    fun isOutsideBand(band: IntRange?, pointer: Point, horizontal: Boolean, margin: Int): Boolean {
        if (band == null) return false
        val c = if (horizontal) pointer.y else pointer.x
        return c < band.first - margin || c > band.last + margin
    }

    /** True once the pointer moved [threshold] px or more away from where it was pressed (the platform's drag dead zone). */
    fun pastThreshold(press: Point, pointer: Point, threshold: Int): Boolean = press.distance(pointer) >= threshold
}

/** Pure grouping of the rectangles that make up a gap into runs that are drawn as one placeholder. Unit tested. */
internal object GapRuns {
    /**
     * [rects] in strip order (empty ones are ignored). Horizontal strips: consecutive rectangles in the same row that touch along
     * the axis (at most [slack] px apart) are one run. Side strips (one line per tab, a pill left of its host in the same line):
     * rectangles in the same line that touch, and lines of the same width that touch, are one run. Every run is the union of its
     * rectangles.
     */
    fun merge(rects: List<Rectangle>, horizontal: Boolean, slack: Int = 2): List<Rectangle> {
        val runs = ArrayList<Rectangle>()
        for (r in rects) {
            if (r.isEmpty) continue
            val last = runs.lastOrNull()
            if (last != null && joins(last, r, horizontal, slack)) last.add(r) else runs += Rectangle(r)
        }
        return runs
    }

    private fun joins(a: Rectangle, b: Rectangle, horizontal: Boolean, slack: Int): Boolean {
        val sameRow = a.y < b.y + b.height && b.y < a.y + a.height
        val touchesX = b.x <= a.x + a.width + slack && b.x + b.width >= a.x - slack
        if (horizontal) return sameRow && touchesX
        val touchesY = b.y <= a.y + a.height + slack && b.y + b.height >= a.y - slack
        val sameWidth = Math.abs(b.x - a.x) <= slack && Math.abs((b.x + b.width) - (a.x + a.width)) <= slack
        return (sameRow && touchesX) || (touchesY && sameWidth)
    }
}
