package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * Pure tab-order functions that keep a group instance contiguous (INV-2) for the menu operations. [order] is the visible tabs of a
 * split, left to right. Pinned tabs are the caller's concern.
 */
object GroupOrder {
    /**
     * CTX-2: [file] (not among [members]) moves to directly after the last of [members] in [order]. Unchanged if none of the members
     * is in [order], for instance a new instance.
     */
    fun <T> orderForJoin(order: List<T>, file: T, members: Collection<T>): List<T> = moveAfterLast(order, file, members.toSet())

    /**
     * CTX-3, and a tab leaving its old group before joining or creating another: [file] (a member) moves to directly after the last
     * other member. Unchanged if it already is the last member, or the only one.
     */
    fun <T> orderForLeave(order: List<T>, file: T, members: Collection<T>): List<T> = moveAfterLast(order, file, members.toSet() - file)

    private fun <T> moveAfterLast(order: List<T>, file: T, others: Set<T>): List<T> {
        val lastOther = order.indexOfLast { it in others }
        if (lastOther < 0) return order
        val fileAt = order.indexOf(file)
        if (fileAt == lastOther + 1) return order
        val rest = order.toMutableList()
        if (fileAt >= 0) rest.removeAt(fileAt)
        rest.add(rest.indexOfLast { it in others } + 1, file)
        return rest
    }
}
