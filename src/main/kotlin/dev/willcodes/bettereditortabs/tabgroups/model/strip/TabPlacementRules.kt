package dev.willcodes.bettereditortabs.tabgroups.model.strip

/** Pure rules for where a newly opened tab goes relative to groups. No platform types, unit tested. */
object TabPlacementRules {
    /** One group instance as the placement rule sees it. */
    data class Span<T>(val groupId: String, val members: Set<T>, val collapsed: Boolean)

    /**
     * [order] is the tab order right after [newTab] was opened (hidden tabs of collapsed groups included, wherever the
     * platform keeps them). If the platform put the new tab strictly inside an expanded group's span, it must go immediately after
     * the group's last tab instead. Returns the corrected order, or null if the tab can stay where it is.
     *
     * No change for: a pinned tab (it lives in the pinned zone), a file that is an explicit member of a group (it was moved into
     * the split together with its group), and a tab that is already outside every span (before the first member, after the last,
     * or "open tabs at the end" is set). Collapsed groups are skipped: their members are hidden, so the new tab can't land between
     * them.
     */
    fun <T> orderForNewTab(order: List<T>, newTab: T, groups: List<Span<T>>, isPinned: (T) -> Boolean): List<T>? {
        if (isPinned(newTab) || groups.any { newTab in it.members }) return null
        val at = order.indexOf(newTab)
        if (at < 0) return null
        for (g in groups) {
            if (g.collapsed) continue
            val first = order.indexOfFirst { it in g.members }
            val last = order.indexOfLast { it in g.members }
            if (first < 0 || at !in (first + 1) until last) continue
            val rest = order.filter { it != newTab }
            val lastInRest = rest.indexOfLast { it in g.members }
            return rest.subList(0, lastInRest + 1) + newTab + rest.subList(lastInRest + 1, rest.size)
        }
        return null
    }
}
