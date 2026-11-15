package dev.willcodes.bettereditortabs.tabgroups.model.strip

/**
 * Where the dragged tab stands when a hold behind a collapsed (or partly shown) group
 * expands it during a drag. The slot follows the pointer: it is worked out against the group's **expanded** layout (the header, then its
 * tabs at their full length), before the expand animation starts, so the placeholder takes its place at once and the tabs grow around it.
 *
 * Positions are along the strip (x for Top / Bottom, y for Left / Right), in the strip's coordinates.
 */
object ExpandSlot {
    /**
     * The number of the group's tabs that stand in front of the placeholder (0 = the group's first slot, right behind its header;
     * [lengths].size = its last slot, behind its last tab): the slot whose placeholder centre is closest to [pointer], the placeholder being
     * [gap] long and the group's tabs, in order, [lengths] long from [start] (the end of the header). A tie goes to the earlier slot.
     */
    fun choose(start: Int, lengths: List<Int>, gap: Int, pointer: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        var at = start
        for (k in 0..lengths.size) {
            val distance = kotlin.math.abs(at + gap / 2 - pointer)
            if (distance < bestDistance) {
                best = k
                bestDistance = distance
            }
            if (k < lengths.size) at += lengths[k]
        }
        return best
    }
}
