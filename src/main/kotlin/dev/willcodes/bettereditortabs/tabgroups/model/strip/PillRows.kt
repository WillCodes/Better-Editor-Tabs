package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle

/**
 * Pure rules for the rows a pill takes in a side strip (Left / Right placement). No platform types, unit tested.
 *
 * In a side strip the platform takes every label's height from `EditorTabLabel.getPreferredHeight()`, which depends on the tab bar only,
 * so a pill cannot borrow height from a label the way it borrows width in a top / bottom strip. Instead the labels are moved down after
 * the platform's layout: every block (a pill row, or the space opened for a group dragged in from another split) pushes the labels
 * behind it down by its height, and sits right above (leading) or below (trailing) the label that hosts it.
 */
object PillRows {
    /**
     * The y shift of every label: the heights of all blocks in front of it, including the leading and trailing blocks of earlier labels,
     * less what earlier labels lost to a collapse / expand animation ([shrink]: how much shorter than its laid-out height each label
     * is drawn; missing entries are 0). Labels behind a shrinking one move up by what it lost.
     */
    fun shifts(leading: List<Int>, trailing: List<Int>, shrink: IntArray = IntArray(0)): IntArray {
        val out = IntArray(leading.size)
        var acc = 0
        for (i in leading.indices) {
            acc += leading[i]
            out[i] = acc
            acc += trailing.getOrElse(i) { 0 } - shrink.getOrElse(i) { 0 }
        }
        return out
    }

    /**
     * The blocks of one (already shifted) [label]: [leading] heights stacked right above it, top to bottom, [trailing] heights right
     * below it. Every block is as wide as the label.
     */
    fun blocks(label: Rectangle, leading: List<Int>, trailing: List<Int>): List<Rectangle> {
        val out = ArrayList<Rectangle>()
        var y = label.y - leading.sum()
        for (h in leading) {
            out += Rectangle(label.x, y, label.width, h)
            y += h
        }
        y = label.y + label.height
        for (h in trailing) {
            out += Rectangle(label.x, y, label.width, h)
            y += h
        }
        return out
    }

    /**
     * How far everything has to move back up (never more than [total], the height of all blocks) so that what the platform keeps in
     * view stays in view after the shift. The platform scrolls by raw label heights, so after the shift the selected label (when the
     * platform follows the selection) or the last element (when the strip is scrolled to its end) can lie up to [total] too far down.
     *
     * The selected label is brought back even when the platform does not overflow ([overflowing] false: the strip fits raw and only
     * the blocks push its end out). The platform cannot scroll such a strip, so without this the selected tab would simply be gone;
     * the start of the strip scrolls away instead and comes back when a tab near the start is selected. The end of the strip is only
     * followed when the platform overflows, because "scrolled to the end" means nothing otherwise.
     */
    fun extraScroll(selectedEnd: Int?, lastEnd: Int, limit: Int, total: Int, followsSelection: Boolean, atEnd: Boolean, overflowing: Boolean): Int {
        if (total <= 0) return 0
        var c = 0
        if (followsSelection && selectedEnd != null) c = maxOf(c, selectedEnd - limit)
        if (overflowing && atEnd) c = maxOf(c, lastEnd - limit)
        return c.coerceIn(0, total)
    }

    /**
     * Where a shifted [label] of a side strip may be shown, given the strip's overflow [buttons] ("more" and the entry point, in the
     * strip's last row) and the end of the strip ([stripEnd]); null when it must be hidden. Mirrors what the platform does with its own
     * labels (`SingleRowLayoutStrategy.Vertical.getLayoutRec`): a label that shares a row with the buttons is narrowed so that it ends
     * before them, and a label that does not fit above the end of the strip is not shown at all. A shifted label must never cover
     * the buttons.
     */
    fun fit(label: Rectangle, buttons: List<Rectangle>, stripEnd: Int): Rectangle? {
        if (label.isEmpty) return null
        if (label.y + label.height > stripEnd) return null
        var start = label.x
        var end = label.x + label.width
        for (b in buttons) {
            if (b.isEmpty) continue
            val sharesRow = b.y < label.y + label.height && b.y + b.height > label.y
            if (!sharesRow || b.x >= end || b.x + b.width <= start) continue
            // keep the larger side of the label that the button leaves free
            if (b.x - start >= end - (b.x + b.width)) end = b.x else start = b.x + b.width
        }
        if (end - start < label.width * MIN_SHOWN_FRACTION) return null
        return Rectangle(start, label.y, end - start, label.height)
    }

    /**
     * Where the platform puts the "more" button of a New UI side strip when it overflows: a copy of
     * `SingleRowLayoutStrategy.Left.getMoreRect` / `Right.getMoreRect` (`singleRow/SingleRowLayoutStrategy.java:505-526` / `571-585`,
     * New UI branch; the strategies are package private). Inputs as the platform reads them: [stripWidth] / [stripHeight] the tabs
     * component's size, [headerWidth] `headerFitSize.width` (the strip's width), [entry] / [more] the preferred sizes of the entry point
     * and of the "more" toolbar, [passInsets] `SingleRowPassInfo.insets`, [actions] `getActionsInsets()`.
     */
    fun sideMoreRect(left: Boolean, stripWidth: Int, stripHeight: Int, headerWidth: Int, entry: Dimension, more: Dimension, passInsets: Insets, actions: Insets): Rectangle {
        val x = if (left) headerWidth - entry.width - more.width - passInsets.right - actions.right - 1
        else stripWidth - more.width - entry.width - actions.right
        return Rectangle(x, stripHeight - more.height - passInsets.bottom - actions.bottom, more.width, more.height)
    }

    /** A label narrowed by the overflow buttons to less than this part of its width is hidden instead. */
    const val MIN_SHOWN_FRACTION = 0.4
}
