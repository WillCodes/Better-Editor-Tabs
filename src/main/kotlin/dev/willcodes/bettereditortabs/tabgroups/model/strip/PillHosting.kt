package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Rectangle

/**
 * Pure rules for hosting group pills inside the platform's own tab layout. No platform types, unit tested.
 *
 * A pill is not laid out by us. Its space is part of a tab label: the *host* label gets a leading (or trailing) border inset
 * of the pill's length, so every platform layout (single row, scrollable, multi row, side placements, overflow decision)
 * already counts it. The pill component is then placed over that reserved area.
 */
object PillHosting {
    /** Which visible tab hosts a pill, and on which side of the tab the space is reserved. */
    data class Host(val index: Int, val leading: Boolean)

    /**
     * The host of the pill of one group.
     *
     * - Expanded group (some member is visible): the first member that is not being dragged by the platform, leading side.
     *   The dragged member is skipped so the pill does not travel with it; it is only used if it is the single visible member.
     * - Collapsed group (no visible member): the first visible, non-dragged tab that did not stand before the group
     *   ([before]), leading side; if there is none, the last non-dragged tab, trailing side. Null when there is no tab at all.
     *
     * @param visible the visible files in strip order (null entries are tabs that are not files)
     * @param before the tabs that stood in front of the collapsed group (the anchor and everything before it)
     */
    fun <T : Any> hostFor(visible: List<T?>, dragged: T?, isMember: (T) -> Boolean, before: Set<T>): Host? {
        val members = visible.indices.filter { i -> visible[i]?.let(isMember) == true }
        if (members.isNotEmpty()) {
            val first = members.firstOrNull { visible[it] != dragged } ?: members.first()
            return Host(first, leading = true)
        }
        val candidates = visible.indices.filter { visible[it] != null && visible[it] != dragged }
        if (candidates.isEmpty()) return visible.indices.firstOrNull()?.let { Host(it, leading = true) }
        val after = candidates.firstOrNull { visible[it] !in before }
        if (after != null) return Host(after, leading = true)
        return Host(candidates.last(), leading = false)
    }

    /**
     * The host of a **parked** pill (DRAG-8: its group's only tab is being dragged, the pill stays where the group was) once the drag
     * has decided on which side of that pill the dragged tab stands ([draggedInFront]). The pill stands right behind the last tab of
     * [before] (the tabs that stood in front of the group when the drag began); when the dragged tab is in front of the pill and stands
     * there too, the pill stands right behind the dragged tab. It is hosted leading by the next visible tab (the dragged one included:
     * standing right behind the pill it hosts it, sharing its leading side with the pill of a group it now leads, whose own pill comes
     * last, see [orderShared]), or trailing by the tab in front when there is no next tab. Null when there is no tab at all.
     *
     * Why (user report, round 3, from a trace): hosting by tab order alone put the pill on the wrong side of the dragged tab in two
     * cases (the tab moved into the front of the next group; the tab at the end of the strip moved in front of its own pill), so the
     * strip did not show what the drop slot said, and the slot flipped between the two readings on every evaluation.
     */
    fun <T : Any> hostForParked(visible: List<T?>, dragged: T, before: Set<T>, draggedInFront: Boolean): Host? {
        if (visible.none { it != null }) return null
        val lastBefore = visible.indices.lastOrNull { i -> visible[i]?.let { it != dragged && it in before } == true } ?: -1
        val at = visible.indexOf(dragged)
        val anchor = if (draggedInFront && at > lastBefore) at else lastBefore
        // the pill stands right behind [anchor]: hosted leading by the next tab, or trailing by the tab at [anchor] when nothing follows
        val next = visible.indices.firstOrNull { it > anchor && visible[it] != null }
        if (next != null) return Host(next, leading = true)
        val behind = visible.indices.lastOrNull { it <= anchor && visible[it] != null }
            ?: return visible.indices.firstOrNull { visible[it] != null }?.let { Host(it, leading = true) }
        return Host(behind, leading = false)
    }

    /**
     * The order of the pills that share one side of one host label (left to right). Several pills meet there when collapsed groups
     * stand next to each other or in front of an expanded group, so their order must come from where each group stood, never from
     * the order of the groups list:
     * - an [isOwn] pill (an expanded group hosted by its own first tab) comes last, right before its tab;
     * - a pill with a parent (a collapsed group that stood right behind a tab of the collapsed group [parentOf] returns) comes
     *   directly after that parent and the parent's other descendants that rank lower;
     * - pills without a parent here (roots) and the children of one parent are ordered by [siblingRank]: for roots the order in which
     *   the groups collapsed (a group collapsing later next to an already collapsed one stood behind it), for children the position of
     *   their anchor tab in the parent. Ties keep the input order.
     * A parent cycle (inconsistent bookkeeping) cannot lose an item: whatever was not reached is appended in input order.
     */
    fun <K : Any> orderShared(items: List<K>, parentOf: (K) -> K?, siblingRank: (K) -> Long, isOwn: (K) -> Boolean): List<K> {
        if (items.size < 2) return items
        val own = items.filter(isOwn)
        val rest = items.filterNot(isOwn)
        val parents = rest.associateWith { k -> parentOf(k)?.takeIf { p -> p !== k && p in rest } }
        val children = rest.groupBy { parents[it] }
        val out = ArrayList<K>(items.size)
        val seen = HashSet<K>()
        fun emit(k: K) {
            if (!seen.add(k)) return
            out += k
            children[k].orEmpty().sortedBy(siblingRank).forEach(::emit)
        }
        children[null].orEmpty().sortedBy(siblingRank).forEach(::emit)
        rest.forEach { if (it !in seen) emit(it) }
        return out + own
    }

    /** The tabs that stand in front of a collapsed group: [anchor] and everything before it. Empty if there is no anchor. */
    fun <T : Any> tabsBefore(visible: List<T?>, anchor: T?): Set<T> {
        val i = if (anchor == null) -1 else visible.indexOf(anchor)
        return if (i < 0) emptySet() else visible.subList(0, i + 1).filterNotNull().toSet()
    }

    /**
     * Where a pill is painted. [full] is the pill's whole area (inside its host label's reserved inset), [visible] the part
     * that lies inside the label and the viewport (empty when it is scrolled out). The component gets [visible] as its bounds (in a side strip [row] is the whole row of the pill)
     * and paints the pill shifted by `visible - full`, so a partly scrolled pill is cut like a tab is, not squeezed.
     */
    class PillRect(val full: Rectangle, val visible: Rectangle, val row: Rectangle = visible) {
        val offsetX: Int get() = visible.x - full.x
        val offsetY: Int get() = visible.y - full.y
    }

    /**
     * Rectangles of the pills of one host [label] (tab-bar coordinates). [leading] lengths are placed from the label's left edge,
     * left to right; [trailing] lengths end at its right edge. Every pill is as high as the label. [viewport] is the visible
     * part of the strip around the label (for example `0..limit` along the main axis).
     *
     * [fullWidth] is the width the label has when the platform does not cut it (its layout length): a scrollable single-row strip cuts the
     * label at the end of the visible area (`ScrollableSingleRowLayout.applyTabLayout`, `drawPartialOverflowTabs`) and gives it only the
     * visible part as its bounds. The reserved space still belongs to the whole label, so trailing items end at the right edge of the
     * uncut label, outside the visible part when the label is cut there, instead of sliding left over the label's content; what
     * is visible of an item is what lies inside the (cut) [label] bounds, exactly what is visible of a tab.
     */
    fun pillRects(
        label: Rectangle, leading: List<Int>, trailing: List<Int>, viewport: Rectangle, fullWidth: Int = label.width,
    ): List<PillRect> {
        val result = ArrayList<PillRect>()
        var x = label.x
        for (length in leading) {
            result += rect(label, x, length, viewport)
            x += length
        }
        x = label.x + maxOf(fullWidth, label.width) - trailing.sum()
        for (length in trailing) {
            result += rect(label, x, length, viewport)
            x += length
        }
        return result
    }

    /**
     * The space to reserve on a label's trailing side for items of [lengths]: their sum plus [labelGap], the platform's step between
     * neighbouring labels (`next.x = label.x + label.width + labelGap`; -1 in the New UI, labels overlap by a pixel). Trailing items end
     * at the label's right edge, so they then start exactly where a following label (whose leading items start at its left edge) would
     * start: a collapsed group at the end of the strip, hosted on the last tab's trailing side, does not move when a tab or group
     * lands behind it and it moves to that label's leading side. The overlap pixel lies under the first item, inside the label.
     */
    fun trailingReserve(lengths: List<Int>, labelGap: Int): Int {
        val sum = lengths.sum()
        return if (sum <= 0) 0 else maxOf(0, sum + labelGap)
    }

    /**
     * [rects] (from [pillRects], [trailingCount] of them trailing, the last ones) must not lie over what the label really shows: when
     * the label's content reaches to [contentRight] (the right edge of its icon, text and close button, as laid out by the platform),
     * the trailing items start there at the earliest, keeping their order and their lengths. What is pushed past the label's right edge
     * or the [viewport] is simply not visible, like the part of a tab that the platform cuts. A no-op when the content ends before the
     * items, which is how a label that is laid out as [pillRects] assumes looks.
     */
    fun clearOfContent(label: Rectangle, rects: List<PillRect>, trailingCount: Int, contentRight: Int, viewport: Rectangle): List<PillRect> {
        if (trailingCount <= 0 || rects.size < trailingCount) return rects
        val firstTrailing = rects.size - trailingCount
        val from = rects[firstTrailing].full.x
        if (contentRight <= from) return rects
        val shift = contentRight - from
        return rects.mapIndexed { i, r ->
            if (i < firstTrailing) r else rect(label, r.full.x + shift, r.full.width, viewport)
        }
    }

    private fun rect(label: Rectangle, x: Int, length: Int, viewport: Rectangle): PillRect {
        val full = Rectangle(x, label.y, length, label.height)
        val visible = full.intersection(label).intersection(viewport)
        return PillRect(full, if (visible.width <= 0 || visible.height <= 0) Rectangle() else visible)
    }
}
