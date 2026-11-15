package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * A group: a project-level identity. An empty [name] means unnamed. Where the group has tabs is described by its
 * [GroupInstance]s, one per split at most. [rule] is the group's auto-group rule (at most one; null = none), shared by every
 * instance.
 */
class TabGroup(val id: String, var name: String, var color: GroupColor, var rule: AutoGroupRule? = null) {
    /**
     * The user chose to keep this group while it has no tabs (an empty group made with New Group, or the
     * answer Keep to the "delete it?" question). Session only, never persisted (empty groups are dropped on restart). Cleared by
     * [GroupsState] when an instance of the group gets a member again, so closing its last tab later removes it as before.
     */
    var keepWhileEmpty: Boolean = false

    /** True if the group has an enabled auto-group rule. */
    val hasEnabledRule: Boolean get() = rule?.enabled == true

    /** An enabled rule, or [keepWhileEmpty], keeps the group's empty instances (exempt from the empty-instance cleanup, not from the startup cleanup). */
    val keepsEmptyInstances: Boolean get() = hasEnabledRule || keepWhileEmpty
}

/**
 * A group's presence in one split: its member files in tab order and its [collapsed] flag. Mutated only by [GroupsState],
 * which enforces that a file is in at most one instance per split.
 */
class GroupInstance<F> internal constructor(val groupId: String, members: List<F>, collapsed: Boolean) {
    private val list: MutableList<F> = members.toMutableList()

    /** Members in tab order. */
    val members: List<F> get() = list

    /** The group's tabs are hidden (all of them, or all but [peek]). Setting it clears [peek]. */
    var collapsed: Boolean = collapsed
        set(value) {
            field = value
            peek = null
        }

    /**
     * Partial expand: while [collapsed], the one member shown beside the header (the file the user navigated to); null = fully
     * collapsed. Cleared when the instance is expanded or collapsed again ([collapsed]) and when the file leaves the group. Session
     * only: not persisted (only the collapsed flag is stored).
     */
    var peek: F? = null
        internal set

    /** The number of members hidden now: all while collapsed without a peek, all but the peek with one, none when expanded. */
    val hiddenCount: Int get() = if (!collapsed) 0 else if (peek != null) list.size - 1 else list.size

    operator fun contains(file: F): Boolean = file in list

    internal fun add(file: F) { list.add(file) }
    internal fun remove(file: F): Boolean {
        if (peek == file) peek = null
        return list.remove(file)
    }
}

/**
 * One instance of a split as given by a drag result (`replaceSplit`): group id, members in order, collapsed flag, and the member shown
 * partially (kept only if the instance is still collapsed and still has it).
 */
class InstanceLayout<F>(val groupId: String, val members: List<F>, val collapsed: Boolean, val peek: F? = null)
