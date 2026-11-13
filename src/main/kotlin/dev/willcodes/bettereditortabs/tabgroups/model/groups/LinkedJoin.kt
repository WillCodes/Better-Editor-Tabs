package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * SPLIT-2 as pure rules: a file of group G in one split that is opened in another split joins G's instance there.
 */
object LinkedJoin {
    /**
     * Whether a file that was just opened in a split may join a group there: not a pinned tab (INV-3), not a preview tab (INV-4), and
     * not already a member of a group of that split ([groupedHere]; INV-1).
     */
    fun eligible(pinned: Boolean, preview: Boolean, groupedHere: Boolean): Boolean = !pinned && !preview && !groupedHere

    /**
     * The group to join when the file belongs to different groups in other splits: [candidates] are (split, group id) pairs, one per
     * other split in which the file is grouped; the group of the split that comes first in [recent] (most recently focused first) wins.
     * A split missing from [recent] (already gone, for instance the half of an unsplit) ranks after every listed one; ties keep the
     * order of [candidates]. Null without candidates.
     */
    fun <S> pickGroup(candidates: List<Pair<S, String>>, recent: List<S>): String? {
        val rank = recent.withIndex().associate { it.value to it.index }
        return candidates.withIndex()
            .minWithOrNull(compareBy({ rank[it.value.first] ?: Int.MAX_VALUE }, { it.index }))
            ?.value?.second
    }

    /** A file that joins [groupId], with its place in the members of the instance it was taken from ([sourceIndex]). */
    data class Pick<F>(val file: F, val groupId: String, val sourceIndex: Int)

    /**
     * The order in which several files that were opened together join their groups (each joins at the end of its group): the groups in
     * the order they first appear in [picks], and within a group the order the files have in the instance they came from. So the
     * tabs an unsplit brings over line up at the end of the group as they stood in the merged-away split (user request 2026-11-13),
     * whatever order the platform opened them in.
     */
    fun <F> joinOrder(picks: List<Pick<F>>): List<Pick<F>> {
        val groupRank = LinkedHashMap<String, Int>()
        for (p in picks) groupRank.putIfAbsent(p.groupId, groupRank.size)
        return picks.withIndex()
            .sortedWith(compareBy({ groupRank.getValue(it.value.groupId) }, { it.value.sourceIndex }, { it.index }))
            .map { it.value }
    }
}
