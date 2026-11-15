package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * A group: a project-level identity (§2). An empty [name] means unnamed. Where the group has tabs is described by its
 * [GroupInstance]s, one per split at most. [rule] is the group's auto-group rule (RULE-1, at most one; null = none), shared by every
 * instance (SPLIT-3).
 */
class TabGroup(val id: String, var name: String, var color: GroupColor, var rule: AutoGroupRule? = null) {
    /**
     * MEM-5 (amended 2026-11-14): the user chose to keep this group while it has no tabs (an empty group made with New Group, or the
     * answer Keep to the "delete it?" question). Session only, never persisted (PERS-3 drops empty groups on restart). Cleared by
     * [GroupsState] when an instance of the group gets a member again, so closing its last tab later removes it as before (MEM-1).
     */
    var keepWhileEmpty: Boolean = false

    /** True if the group has an enabled auto-group rule (RULE-6). */
    val hasEnabledRule: Boolean get() = rule?.enabled == true

    /** RULE-6, MEM-5 amended: an enabled rule, or [keepWhileEmpty], keeps the group's empty instances (exempt from MEM-5, not from the startup cleanup, PERS-3). */
    val keepsEmptyInstances: Boolean get() = hasEnabledRule || keepWhileEmpty
}

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
