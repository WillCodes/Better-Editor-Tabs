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

/**
 * The order in which the instances of a split must be listed so that they agree with the strip: by the position of each instance's
 * first member in [order] (the split's tab order with hidden tabs at their header's place). Instances with no member in [order] keep
 * their relative order after the others. [instances] are (group id, members) pairs in their current list order.
 */
fun <T> instanceStripOrder(instances: List<Pair<String, List<T>>>, order: List<T>): List<String> {
    val position = HashMap<T, Int>()
    order.forEachIndexed { i, f -> position.putIfAbsent(f, i) }
    return instances.withIndex()
        .sortedWith(compareBy({ iv -> iv.value.second.minOfOrNull { position[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value.first }
}
