package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration

/**
 * What the drag layer reads from and reports to the owner of one split's group state (the `SplitBar`, which forwards to `TabBarHost`).
 * Internal to `tabbar/`: it is not a second public contract.
 */
internal interface SplitGroups {
    /** The groups currently decorating this split, in the form handed to `TabBarAdapter.setGroups`. */
    fun groups(): List<GroupDecoration>

    /** The drag layer changed membership / order. [groups] is the complete new state (collapsed flags unchanged). */
    fun commit(groups: List<GroupDecoration>)

    /** A whole group instance from another split was dropped here (INT-6, SPLIT-5). Already merged with an existing instance if any. */
    fun acceptGroup(group: GroupDecoration)
}
