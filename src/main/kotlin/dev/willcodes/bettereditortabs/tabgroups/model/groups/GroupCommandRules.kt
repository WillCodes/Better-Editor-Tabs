package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * Where a group's header stands in a split's logical tab order (§13, Move Tab to Next / Previous Group): [first] is the number of tabs
 * in front of the header (for a group with open tabs the index of its first member; for an empty group the place its pill keeps), and
 * [count] the number of open members. The group's tabs occupy `first until first + count`.
 */
data class GroupSpan(val id: String, val first: Int, val count: Int) {
    /** Index of the last member; `first - 1` for an empty group (its pill stands right behind the tab before it). */
    val last: Int get() = first + count - 1
}

/** The one-line state of a group instance the bulk commands decide on (§13: Collapse Other Groups, Expand All Groups). */
data class InstanceState(val id: String, val collapsed: Boolean, val partlyShown: Boolean, val openTabs: Int)

/**
 * The decidable part of the §13 actions that move or select by group order, and of the bulk collapse / expand commands. Pure; the
 * service gathers the inputs (`GroupCommands` in the service package).
 */
object GroupCommandRules {
    /**
     * Move Tab to Next Group: the group a tab goes to. [strip] is the split's groups in header order. A grouped tab ([ownGroup] in [strip])
     * goes to the group right after its own; an ungrouped one (or one whose group has no header) to the first group whose header comes
     * after the tab ([tabIndex], its index in the logical order). Empty groups count. No wrap-around: null at the end.
     */
    fun nextGroup(strip: List<GroupSpan>, ownGroup: String?, tabIndex: Int): String? {
        val own = strip.indexOfFirst { it.id == ownGroup }
        return if (own >= 0) strip.getOrNull(own + 1)?.id else strip.firstOrNull { it.first > tabIndex }?.id
    }

    /**
     * Move Tab to Previous Group: the group right before the tab's own, or for an ungrouped tab the last group whose span lies before it.
     * An empty group lies before the tab if its pill stands in front of it. Null at the start.
     */
    fun previousGroup(strip: List<GroupSpan>, ownGroup: String?, tabIndex: Int): String? {
        val own = strip.indexOfFirst { it.id == ownGroup }
        return if (own >= 0) strip.getOrNull(own - 1)?.id else strip.lastOrNull { it.last < tabIndex }?.id
    }

    /**
     * Select Next / Previous Tab in Group: the member after ([step] = 1) or before ([step] = -1) [current] in [members] (open members in
     * logical order), cycling at the ends. Null with fewer than two members or when [current] is not one of them.
     */
    fun <T> cycle(members: List<T>, current: T, step: Int): T? {
        if (members.size < 2) return null
        val at = members.indexOf(current)
        if (at < 0) return null
        return members[Math.floorMod(at + step, members.size)]
    }

    /**
     * Collapse Other Groups: the instances to collapse, every one that has open tabs and is expanded or partly shown, except [keep] (the
     * current group, null for an ungrouped tab). Empty instances are never touched. Nothing to do: empty list.
     */
    fun collapseOthers(instances: List<InstanceState>, keep: String?): List<String> =
        instances.filter { it.id != keep && it.openTabs > 0 && (!it.collapsed || it.partlyShown) }.map { it.id }

    /** Expand All Groups: every instance with open tabs that is collapsed or partly shown. */
    fun expandAll(instances: List<InstanceState>): List<String> =
        instances.filter { it.openTabs > 0 && it.collapsed }.map { it.id }

    /**
     * Toggle Collapse of Current Group: the group to toggle. The context tab's group if it has one; for an ungrouped tab the group the
     * action collapsed last in this split ([remembered]), if it is still [rememberedCollapsed]; otherwise null (disabled).
     */
    fun toggleTarget(contextGroup: String?, remembered: String?, rememberedCollapsed: Boolean): String? =
        contextGroup ?: remembered.takeIf { it != null && rememberedCollapsed }
}
