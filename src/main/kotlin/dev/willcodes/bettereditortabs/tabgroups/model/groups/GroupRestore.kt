package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * Where a group that is brought back goes in a split's logical tab order. Built with [end], [index] or [after].
 * Whatever is asked for, [GroupRestore.plan] moves the place so that it never splits another group and never lands before pinned tabs.
 */
class RestorePlace<F> private constructor(private val kind: Kind, internal val file: F?, internal val index: Int) {
    private enum class Kind { END, INDEX, AFTER }

    internal val isEnd get() = kind == Kind.END
    internal val isAfter get() = kind == Kind.AFTER

    companion object {
        /** At the end of the strip. */
        fun <F> end(): RestorePlace<F> = RestorePlace(Kind.END, null, 0)

        /** At position [index] of the order without the group's own tabs (clamped). */
        fun <F> index(index: Int): RestorePlace<F> = RestorePlace(Kind.INDEX, null, index)

        /** Right after [file]; if that is no longer open, at [fallbackIndex] like [index]. */
        fun <F> after(file: F, fallbackIndex: Int): RestorePlace<F> = RestorePlace(Kind.AFTER, file, fallbackIndex)
    }
}

/**
 * The outcome of [GroupRestore.plan]. [order]: the split's whole logical order afterwards (files that are not open yet included);
 * [joining]: the files that become members of the group's instance and are placed as one block, in snapshot order (members that the
 * instance has already are not in it); [skippedPinned] and [skippedOtherGroup]: snapshot files that stay out.
 */
class RestorePlan<F>(val order: List<F>, val joining: List<F>, val skippedPinned: List<F>, val skippedOtherGroup: List<F>)

/**
 * The membership and order rules for bringing a group back into a split (restore from the stash, reopen a deleted group).
 * Pure.
 *
 * - A snapshot file that is open in the split and ungrouped joins the group, and its tab moves to the group's place.
 * - One that is a member of a *different* group of the split stays where it is and is not added.
 * - One that is pinned in the split is not added.
 * - If the split already has an instance of the same group, the group's place is right after that instance's last tab (it stays
 *   contiguous); files that are members of it already stay where they are.
 * - The members that join are inserted as one block, in snapshot order, into the order without them, at the place. The place is moved
 *   past pinned tabs (they stay first) and, if it is inside another group, to after that group's last tab.
 */
object GroupRestore {
    /**
     * @param snapshot the group's files in group order (only files that still exist).
     * @param order the split's logical order: every open tab, hidden tabs of collapsed groups at their header's place.
     * @param groups the split's group instances as (group id, members).
     * @param pinned the files pinned in the split.
     * @param groupId the group that comes back.
     */
    fun <F> plan(
        snapshot: List<F>,
        order: List<F>,
        groups: List<Pair<String, List<F>>>,
        pinned: Set<F>,
        groupId: String,
        place: RestorePlace<F>,
    ): RestorePlan<F> {
        val own = groups.firstOrNull { it.first == groupId }?.second.orEmpty()
        val ownSet = own.toSet()
        val groupOf = HashMap<F, String>()
        for ((id, members) in groups) for (f in members) groupOf.putIfAbsent(f, id)
        val joining = ArrayList<F>()
        val skippedPinned = ArrayList<F>()
        val skippedOther = ArrayList<F>()
        for (file in snapshot.distinct()) {
            when {
                file in ownSet -> {}
                file in pinned -> skippedPinned += file
                groupOf[file] != null -> skippedOther += file
                else -> joining += file
            }
        }
        val block = joining.toSet()
        val base = order.filter { it !in block }
        val afterOwn = base.indices.lastOrNull { base[it] in ownSet }?.plus(1)
        var at = when {
            afterOwn != null -> afterOwn
            place.isEnd -> base.size
            place.isAfter -> base.indexOf(place.file).let { if (it >= 0) it + 1 else place.index }
            else -> place.index
        }.coerceIn(0, base.size)
        // pinned tabs stay first
        val afterPinned = (base.indices.lastOrNull { base[it] in pinned } ?: -1) + 1
        if (at < afterPinned) at = afterPinned
        // never inside another group: move to after its last tab
        if (at in 1 until base.size) {
            val before = groupOf[base[at - 1]]
            if (before != null && before != groupId && before == groupOf[base[at]]) {
                at = base.indices.last { groupOf[base[it]] == before } + 1
            }
        }
        return RestorePlan(base.subList(0, at) + joining + base.subList(at, base.size), joining, skippedPinned, skippedOther)
    }
}
