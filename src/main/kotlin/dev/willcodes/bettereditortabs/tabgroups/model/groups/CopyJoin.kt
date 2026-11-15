package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * A grouped tab copied into another split (Ctrl held when it is dropped there) joins its own group in that split, wherever it was
 * dropped ("dragging a copy"). Pure, unit tested.
 */
object CopyJoin {
    /** [order]: the target split's tab order after the drop; [members]: every instance's members (group id to files), in [order]. */
    data class Result<T>(val order: List<T>, val members: Map<String, List<T>>)

    /**
     * [file] was dropped into [order] (the target split's complete tab order, nothing hidden) and must end up in group [group].
     * [instances] are the target's instances before the drop (group id, members); [file] leaves whichever of them it was dropped into.
     * - The split has an instance of [group]: [file] becomes its last member and its tab goes right behind the instance's last tab
     *   (as adding a tab through the context menu does).
     * - It has none: a new instance with [file] alone is made where it was dropped, except that a drop between two tabs of another
     *   group puts it right behind that group (the group stays contiguous).
     */
    fun <T> place(order: List<T>, file: T, instances: List<Pair<String, List<T>>>, group: String): Result<T> {
        val others = instances.map { (id, m) -> id to m.filter { it != file } }
        val own = others.firstOrNull { it.first == group }?.second.orEmpty()
        val newOrder = if (own.isNotEmpty()) {
            GroupOrder.orderForJoin(order, file, own)
        }
        else {
            val at = order.indexOf(file)
            val inside = others.firstOrNull { (_, m) ->
                m.isNotEmpty() && order.indexOfFirst { it in m } < at && order.indexOfLast { it in m } > at
            }
            if (inside == null) order else GroupOrder.orderForJoin(order, file, inside.second)
        }
        val members = LinkedHashMap<String, List<T>>()
        for ((id, m) in others) members[id] = newOrder.filter { it in m.toSet() || (id == group && it == file) }
        if (group !in members) members[group] = listOf(file)
        return Result(newOrder, members)
    }
}
