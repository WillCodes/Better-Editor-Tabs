package dev.willcodes.bettereditortabs.tabgroups.model.persist

import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitKey
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitMatcher
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitSnapshot

/** An editor split after the restart as the planner sees it: its key and the URLs of its open tabs in the platform's order. */
data class CurrentSplit(val key: SplitKey, val files: List<String>)

/**
 * What to do for the split at index [current] of the list given to [RestorePlanner.plan]: re-order its tabs to [order] (every open
 * file of the split, exactly once), then create [instances] (strip order, only members that reopened, none empty). [manualOrder]: the
 * stored manual order of an alphabetically sorted split, restricted to the files that reopened; empty if none was stored.
 */
data class PlannedSplit(
    val current: Int,
    val order: List<String>,
    val instances: List<StoredInstance>,
    val manualOrder: List<String> = emptyList(),
)

/** [groups]: the identities that stay (referenced by a planned instance); [splits]: one entry per matched split. */
data class RestorePlan(val groups: List<StoredGroup>, val splits: List<PlannedSplit>)

/**
 * The startup restore rules as a pure function, for when the editor layout has been restored.
 *
 * - Stored splits are matched to current splits by [SplitMatcher]: the best overlap of reopened files wins, the split key breaks ties.
 *   A stored split that shares no file with any current split is dropped, so with "Reopen files on startup" off (nothing reopened) no
 *   group survives.
 * - Membership is restored only for tabs that actually reopened (in *that* matched split); members that did not are dropped. A file
 *   belongs to the first instance that lists it.
 * - An instance stored empty (the user kept it, ruled or not) is restored empty at its stored place, counted in the tabs
 *   that reopened.
 * - An instance stored with members of which none reopened is dropped, whatever its group (a rule does not exempt it; a rule fills restored groups
 *   separately). A group without any remaining instance is dropped. The stash is stored apart from the
 *   groups and is not touched by a restore.
 * - Tab order: the stored order restricted to the files that reopened, then the reopened files it did not list in their current
 *   relative order, so tabs that are new since the layout was stored end up behind the known ones.
 */
object RestorePlanner {
    fun plan(stored: StoredGroups, current: List<CurrentSplit>): RestorePlan {
        val matches = match(stored, current)
        val known = stored.groups.associateBy { it.id }

        val splits = ArrayList<PlannedSplit>()
        for ((pi, ci) in matches.entries.sortedBy { it.value }) {
            val open = current[ci].files
            val openSet = open.toSet()
            val s = stored.splits[pi]
            val seen = HashSet<String>()
            val instances = ArrayList<StoredInstance>()
            for (inst in s.instances) {
                if (inst.groupId !in known) continue
                if (inst.members.isEmpty()) {
                    // an instance stored empty (kept by the user) comes back empty at its place, counted in the tabs
                    // that reopened (the place is a number of tabs of the stored order); no place stored = at the end.
                    val place = inst.place?.let { p -> s.order.take(p.coerceAtLeast(0)).count { it in openSet } } ?: open.size
                    instances += StoredInstance(inst.groupId, emptyList(), inst.collapsed, place.coerceIn(0, open.size))
                    continue
                }
                val members = inst.members.filter { it in openSet && seen.add(it) }
                if (members.isNotEmpty()) instances += StoredInstance(inst.groupId, members, inst.collapsed)
            }
            if (instances.isEmpty()) continue
            val listed = s.order.filter { it in openSet }.distinct()
            val listedSet = listed.toSet()
            val manual = s.manualOrder.filter { it in openSet }.distinct()
            splits += PlannedSplit(ci, listed + open.filter { it !in listedSet }, instances, manual)
        }
        restoreStrays(stored, current, matches.keys, known, splits)
        val used = splits.flatMapTo(HashSet()) { p -> p.instances.map { it.groupId } }
        return RestorePlan(stored.groups.filter { it.id in used }, splits)
    }

    /**
     * The empty instances of the stored splits that held no tab at all ([StoredSplit.isEmptyOnly]: a temporary split, or a
     * window whose tabs had closed) and that no current split matched come back in the main area's first split that has an open tab,
     * at the end of its strip (an instance already there is not doubled). With no such split (nothing reopened) they are dropped, as every
     * group is when nothing reopened. Adds to [splits], the planned splits (appends to the target's entry, or creates it).
     */
    private fun restoreStrays(
        stored: StoredGroups,
        current: List<CurrentSplit>,
        matched: Set<Int>,
        known: Map<String, StoredGroup>,
        splits: MutableList<PlannedSplit>,
    ) {
        val strays = stored.splits.filterIndexed { i, s -> i !in matched && s.isEmptyOnly }.flatMap { it.instances }
        if (strays.isEmpty()) return
        val ci = current.indexOfFirst { it.key.container == SplitKey.MAIN && it.files.isNotEmpty() }
        if (ci < 0) return
        val open = current[ci].files
        val at = splits.indexOfFirst { it.current == ci }
        val here = if (at >= 0) splits[at].instances.mapTo(HashSet()) { it.groupId } else HashSet()
        val added = ArrayList<StoredInstance>()
        for (inst in strays) {
            if (inst.groupId !in known || !here.add(inst.groupId)) continue
            added += StoredInstance(inst.groupId, emptyList(), inst.collapsed, open.size)
        }
        if (added.isEmpty()) return
        if (at >= 0) splits[at] = splits[at].copy(instances = splits[at].instances + added)
        else splits += PlannedSplit(ci, open, added)
    }

    private fun match(stored: StoredGroups, current: List<CurrentSplit>): Map<Int, Int> {
        val persisted = stored.splits.map { s -> SplitSnapshot(s.splitKey(), (s.order + s.instances.flatMap { it.members }).toSet()) }
        val live = current.map { SplitSnapshot(it.key, it.files.toSet()) }
        return SplitMatcher.match(persisted, live)
    }

    /**
     * Detached windows that appear late: the stored splits that no split of [current] claims, with the group identities their
     * instances use. They stay *pending* for a limited time ([dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService]
     * decides how long): a window restored after the first pass is matched against them later with [plan]. Same matching as [plan], so
     * a split that [plan] would restore is not pending. [detachedOnly]: only splits of detached windows can be late (the main area is
     * restored as a whole before the first pass, and with "Reopen files on startup" off no group of it may survive), so the
     * splits of the main area are never pending.
     */
    fun pending(stored: StoredGroups, current: List<CurrentSplit>, detachedOnly: Boolean = false): StoredGroups {
        val matched = match(stored, current).keys
        // a split that held no tab at all has no file to wait for: its empty instances were handled by [plan] ([restoreStrays])
        val splits = stored.splits.filterIndexed { i, s -> i !in matched && !s.isEmptyOnly && (!detachedOnly || s.splitKey().container != SplitKey.MAIN) }
        val used = splits.flatMapTo(HashSet()) { s -> s.instances.map { it.groupId } }
        return StoredGroups(stored.groups.filter { it.id in used }, splits)
    }
}
