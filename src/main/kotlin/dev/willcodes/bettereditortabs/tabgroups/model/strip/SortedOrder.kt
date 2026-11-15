package dev.willcodes.bettereditortabs.tabgroups.model.strip

/**
 * The order of a strip while "Sort tabs alphabetically" is on. Pure, no platform types, unit tested.
 *
 * Pinned tabs come first, then every group, then the ungrouped tabs. Groups are ordered by name (the passed comparator, case
 * insensitive), unnamed groups (empty name) last in creation order; the tabs are sorted within each group. [restoreManual] is the way
 * back: the order the strip had before the sort took over.
 */
object SortedOrder {
    /** One tab as the order sees it: [group] is the id of the group instance it belongs to, null if ungrouped. */
    class Tab<K>(val key: K, val title: String, val pinned: Boolean, val group: String?)

    /** One group instance: [creationIndex] orders unnamed groups (and breaks ties between equal names). */
    class Group(val id: String, val name: String, val creationIndex: Int)

    /**
     * The sorted order of [tabs] (given in their current order, hidden tabs of collapsed groups included). Ties between equal titles
     * keep the current relative order (a stable sort). A pinned tab is never part of a group, whatever [Tab.group] says; a
     * group id that is not in [groups] counts as ungrouped; a group without a tab leaves no trace.
     */
    fun <K> order(tabs: List<Tab<K>>, groups: List<Group>, titleOrder: Comparator<String>, nameOrder: Comparator<String>): List<K> {
        val byTitle = Comparator<Tab<K>> { a, b -> titleOrder.compare(a.title, b.title) }
        val known = groups.mapTo(HashSet()) { it.id }
        val pinned = tabs.filter { it.pinned }.sortedWith(byTitle)
        val rest = tabs.filter { !it.pinned }
        val membersOf = rest.filter { it.group != null && it.group in known }.groupBy { it.group!! }
        val ordered = groups.filter { it.id in membersOf }.sortedWith(groupOrder(nameOrder))
        val ungrouped = rest.filter { it.group == null || it.group !in known }.sortedWith(byTitle)
        val result = ArrayList<K>(tabs.size)
        for (t in pinned) result += t.key
        for (g in ordered) for (t in membersOf.getValue(g.id).sortedWith(byTitle)) result += t.key
        for (t in ungrouped) result += t.key
        return result
    }

    /**
     * Where the pills of the empty groups [emptyIds] (groups with an enabled rule and no tab) stand in the sorted
     * strip [sorted] (the result of [order] for [tabs] and [groups]): among the groups, by name like the others. The result maps each
     * empty group to its anchor, the last tab in front of its pill (the last tab of the group before it that has tabs, else the last
     * pinned tab, else null: first), in group order, so that several pills behind one tab keep their order ([EmptyPillSpots]).
     */
    fun <K> emptySpots(
        tabs: List<Tab<K>>, groups: List<Group>, sorted: List<K>, emptyIds: Set<String>, nameOrder: Comparator<String>,
    ): LinkedHashMap<String, K?> {
        val result = LinkedHashMap<String, K?>()
        if (emptyIds.isEmpty()) return result
        val known = groups.mapTo(HashSet()) { it.id }
        val lastOf = HashMap<String, K>()
        val position = HashMap<K, Int>()
        for ((i, k) in sorted.withIndex()) position.putIfAbsent(k, i)
        for (t in tabs) {
            val g = t.group ?: continue
            if (t.pinned || g !in known) continue
            val at = position[t.key] ?: continue
            if (lastOf[g]?.let { position.getValue(it) < at } != false) lastOf[g] = t.key
        }
        var anchor: K? = tabs.filter { it.pinned }.mapNotNull { t -> position[t.key]?.let { t.key to it } }.maxByOrNull { it.second }?.first
        for (g in groups.sortedWith(groupOrder(nameOrder))) {
            val last = lastOf[g.id]
            if (last != null) anchor = last
            else if (g.id in emptyIds) result[g.id] = anchor
        }
        return result
    }

    private fun groupOrder(nameOrder: Comparator<String>): Comparator<Group> = Comparator { a, b ->
        val an = a.name.isEmpty()
        val bn = b.name.isEmpty()
        when {
            an != bn -> if (an) 1 else -1
            an -> a.creationIndex.compareTo(b.creationIndex)
            else -> nameOrder.compare(a.name, b.name).takeIf { it != 0 } ?: a.creationIndex.compareTo(b.creationIndex)
        }
    }

    /**
     * The order to go back to when the sort is no longer ours: [saved] (the manual order remembered when the sort began) without the
     * tabs that are gone, then the tabs [saved] does not know, in their [current] order; normalised: pinned tabs first (their relative
     * order kept), and the members of each group contiguous at the place of the group's first member, in the order they appear in.
     */
    fun <K> restoreManual(saved: List<K>, current: List<K>, groupOf: (K) -> String?, isPinned: (K) -> Boolean): List<K> {
        val open = current.toHashSet()
        val seen = HashSet<K>()
        val base = ArrayList<K>(current.size)
        for (k in saved) if (k in open && seen.add(k)) base += k
        for (k in current) if (seen.add(k)) base += k
        val (pinned, rest) = base.partition(isPinned)
        val members = LinkedHashMap<String, MutableList<K>>()
        for (k in rest) groupOf(k)?.let { members.getOrPut(it) { ArrayList() } += k }
        val result = ArrayList<K>(base.size)
        result += pinned
        val placed = HashSet<String>()
        for (k in rest) {
            val g = groupOf(k)
            if (g == null) result += k else if (placed.add(g)) result += members.getValue(g)
        }
        return result
    }
}
