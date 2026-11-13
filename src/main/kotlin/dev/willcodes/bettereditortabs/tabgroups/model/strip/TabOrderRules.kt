package dev.willcodes.bettereditortabs.tabgroups.model.strip

/**
 * Pure ordering rules used when collapsing and expanding groups. No platform types, so they can be unit tested.
 */
object TabOrderRules {
    /**
     * COLL-2: the tab to select when the selected tab is about to be hidden. The nearest tab outside the group,
     * first to the right, then to the left. Null if every tab is in the group.
     */
    fun <T> nearestOutside(order: List<T>, selectedIndex: Int, inGroup: (T) -> Boolean): T? {
        for (i in selectedIndex + 1 until order.size) if (!inGroup(order[i])) return order[i]
        for (i in minOf(selectedIndex, order.size) - 1 downTo 0) if (!inGroup(order[i])) return order[i]
        return null
    }

    /**
     * COLL-3: the order after a collapsed group comes back: [members] go immediately after [anchor]
     * (the tab that preceded the group when it was collapsed), or at the start if the anchor is gone.
     * [order] must not contain the members.
     */
    fun <T> placeGroup(order: List<T>, members: List<T>, anchor: T?): List<T> {
        val at = if (anchor == null) 0 else order.indexOf(anchor).let { if (it < 0) 0 else it + 1 }
        return order.subList(0, at) + members + order.subList(at, order.size)
    }

    /**
     * The order after a tab was pinned (or unpinned) with "keep pinned tabs on the left": the pinned tabs first, every other tab
     * after them, each part in its order in [before] (the order just before the change). The platform's own grouping swaps a newly
     * pinned tab with the first unpinned tab, which moves that tab behind a group and makes the group look shifted; this keeps every
     * unpinned tab and group where it was. Tabs of [current] missing from [before] keep their place behind it; tabs of [before] that
     * are gone are dropped.
     */
    fun <T> pinnedFirst(before: List<T>, current: List<T>, isPinned: (T) -> Boolean): List<T> {
        val open = current.toHashSet()
        val known = before.filter { it in open }
        val order = known + current.filter { it !in known.toHashSet() }
        return order.filter(isPinned) + order.filterNot(isPinned)
    }

    /** The tab that precedes the first of [members] in [order], or null if the group is first. Used as the collapse anchor. */
    fun <T> anchorBefore(order: List<T>, members: Set<T>): T? {
        val first = order.indexOfFirst { it in members }
        return if (first <= 0) null else order[first - 1]
    }
}
