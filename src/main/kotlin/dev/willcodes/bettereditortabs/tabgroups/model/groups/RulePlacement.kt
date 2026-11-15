package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * One group instance of a split as [RulePlacement] sees it: its [members] in tab order and [headerIndex], the number of tabs of the
 * split's logical order in front of its header (for an empty instance: where its lone pill stands).
 */
class PlacedInstance<F>(val groupId: String, val members: List<F>, val headerIndex: Int)

/**
 * Where the tabs that a rule pulls into a group go in a split's logical tab order (RULE-2, RULE-3, POP-2). Pure.
 *
 * - The group has an instance in the split: the files go right after its last member (an empty instance: at its header, so its pill
 *   ends up in front of them).
 * - It has none: the instance is new and goes after the existing groups of the split, i.e. behind the last member (or header) of the
 *   instance that is last in the strip, so no other group is split and the new one stands last.
 * - The split has no groups at all: the files gather where the first of them stands.
 *
 * Pinned tabs need no handling: they are always in front of every group (INV-3) and an unpinned file is behind them, so no place
 * computed here is in front of a pinned tab.
 */
object RulePlacement {
    /**
     * @param order the split's logical order, the files to pull in included.
     * @param instances the split's instances in strip order.
     * @param groupId the group the files join.
     * @param files the files to pull in, in the order they take afterwards (their current relative order).
     * @return the new logical order: [order] without [files], with them inserted as one block.
     */
    fun <F> place(order: List<F>, instances: List<PlacedInstance<F>>, groupId: String, files: List<F>): List<F> {
        if (files.isEmpty()) return order
        val moving = files.toSet()
        val base = order.filter { it !in moving }
        // a position of `order` counts the moving files in front of it; in `base` they are gone
        fun inBase(position: Int): Int = order.subList(0, position.coerceIn(0, order.size)).count { it !in moving }
        fun afterLastMember(inst: PlacedInstance<F>): Int {
            val own = inst.members.toSet()
            val last = base.indices.lastOrNull { base[it] in own } ?: return inBase(inst.headerIndex)
            return last + 1
        }
        val own = instances.firstOrNull { it.groupId == groupId }
        val at = when {
            own != null -> afterLastMember(own)
            instances.isNotEmpty() -> afterLastMember(instances.last())
            else -> order.indexOfFirst { it in moving }.let { if (it < 0) base.size else inBase(it) }
        }.coerceIn(0, base.size)
        return base.subList(0, at) + files + base.subList(at, base.size)
    }
}
