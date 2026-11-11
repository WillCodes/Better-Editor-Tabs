package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * A group: a project-level identity (§2). An empty [name] means unnamed. Where the group has tabs is described by its
 * [GroupInstance]s, one per split at most. Later stages add the auto-group rule here.
 */
class TabGroup(val id: String, var name: String, var color: GroupColor)

/**
 * A group's presence in one split (§2): its member files in tab order and its [collapsed] flag. Mutated only by [GroupsState],
 * which enforces INV-1 (a file is in at most one instance per split).
 */
class GroupInstance<F> internal constructor(val groupId: String, members: List<F>, var collapsed: Boolean) {
    private val list: MutableList<F> = members.toMutableList()

    /** Members in tab order. */
    val members: List<F> get() = list

    operator fun contains(file: F): Boolean = file in list

    internal fun add(file: F) { list.add(file) }
    internal fun remove(file: F): Boolean = list.remove(file)
}

/** One instance of a split as given by a drag result (`replaceSplit`): group id, members in order, collapsed flag. */
class InstanceLayout<F>(val groupId: String, val members: List<F>, val collapsed: Boolean)
