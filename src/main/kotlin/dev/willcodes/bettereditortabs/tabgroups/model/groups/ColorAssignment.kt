package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * COL-1: the colour for a new group. [fixed] (the COL-2 / SET-3 override) wins when set. Otherwise the first palette colour, in table
 * order, that is not in [inUse]; if all nine are in use, the least-used one, earliest in table order on a tie.
 *
 * [inUse] has one entry per group, not per instance: a group linked across splits counts once.
 */
fun nextColor(inUse: Collection<GroupColor>, fixed: GroupColor? = null): GroupColor {
    if (fixed != null) return fixed
    val counts = GroupColor.values().associateWith { c -> inUse.count { it == c } }
    // minByOrNull returns the first minimum, so ties go to the earliest in table order, and an unused colour (0) always wins.
    return GroupColor.values().minByOrNull { counts.getValue(it) }!!
}
