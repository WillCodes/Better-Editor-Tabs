package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * The colour for a new group. [fixed] (the colour the user chose, an override) wins when set. Otherwise the first palette colour that is not
 * in [inUse], going round the table order from [start] (the starting colour; the first colour by default); if all nine are in
 * use, the least-used one, earliest in that order on a tie.
 *
 * [inUse] has one entry per group, not per instance: a group linked across splits counts once.
 */
fun nextColor(inUse: Collection<GroupColor>, fixed: GroupColor? = null, start: GroupColor = GroupColor.values().first()): GroupColor {
    if (fixed != null) return fixed
    val order = colorOrder(start)
    val counts = order.associateWith { c -> inUse.count { it == c } }
    // minByOrNull returns the first minimum, so ties go to the earliest in order, and an unused colour (0) always wins.
    return order.minByOrNull { counts.getValue(it) }!!
}

/** The palette in table order, rotated so that it begins at [start] and wraps round. */
fun colorOrder(start: GroupColor): List<GroupColor> {
    val all = GroupColor.values().toList()
    return all.drop(start.ordinal) + all.take(start.ordinal)
}
