package dev.willcodes.bettereditortabs.tabgroups.model.persist

import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitKey
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitMatcher
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitSnapshot

/** An editor split after the restart as the planner sees it: its key and the URLs of its open tabs in the platform's order. */
data class CurrentSplit(val key: SplitKey, val files: List<String>)

/**
 * What to do for the split at index [current] of the list given to [RestorePlanner.plan]: re-order its tabs to [order] (every open
 * file of the split, exactly once), then create [instances] (strip order, only members that reopened, none empty).
 */
data class PlannedSplit(val current: Int, val order: List<String>, val instances: List<StoredInstance>)

/** [groups]: the identities that stay (referenced by a planned instance, or kept by the stash hook); [splits]: one entry per matched split. */
data class RestorePlan(val groups: List<StoredGroup>, val splits: List<PlannedSplit>)

/**
 * The startup rules of PERS-3 as a pure function, for when the editor layout has been restored.
 *
 * - Stored splits are matched to current splits by [SplitMatcher]: the best overlap of reopened files wins, the split key breaks ties.
 *   A stored split that shares no file with any current split is dropped, so with "Reopen files on startup" off (nothing reopened) no
 *   group survives.
 * - Membership is restored only for tabs that actually reopened (in *that* matched split); members that did not are dropped. A file
 *   belongs to the first instance that lists it (INV-1).
 * - An instance without a reopened member is dropped, whatever its group (a rule does not exempt it, RULE-5 fills restored groups
 *   separately). A group without any remaining instance is dropped (MEM-6) unless [keepWithoutInstances] says it is stashed
 *   (Stage 10: stashed groups are unaffected by a restart); until then no group is.
 * - Tab order: the stored order restricted to the files that reopened, then the reopened files it did not list in their current
 *   relative order, so tabs that are new since the layout was stored end up behind the known ones.
 */
object RestorePlanner {
    fun plan(
        stored: StoredGroups,
        current: List<CurrentSplit>,
        keepWithoutInstances: (StoredGroup) -> Boolean = { false },
    ): RestorePlan {
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
                val members = inst.members.filter { it in openSet && seen.add(it) }
                if (members.isNotEmpty()) instances += StoredInstance(inst.groupId, members, inst.collapsed)
            }
            if (instances.isEmpty()) continue
            val listed = s.order.filter { it in openSet }.distinct()
            val listedSet = listed.toSet()
            splits += PlannedSplit(ci, listed + open.filter { it !in listedSet }, instances)
        }
        val used = splits.flatMapTo(HashSet()) { p -> p.instances.map { it.groupId } }
        return RestorePlan(stored.groups.filter { it.id in used || keepWithoutInstances(it) }, splits)
    }

    private fun match(stored: StoredGroups, current: List<CurrentSplit>): Map<Int, Int> {
        val persisted = stored.splits.map { s -> SplitSnapshot(s.splitKey(), (s.order + s.instances.flatMap { it.members }).toSet()) }
        val live = current.map { SplitSnapshot(it.key, it.files.toSet()) }
        return SplitMatcher.match(persisted, live)
    }

    /**
     * PERS-3, detached windows that appear late: the stored splits that no split of [current] claims, with the group identities their
     * instances use. They stay *pending* for a limited time ([dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService]
     * decides how long): a window restored after the first pass is matched against them later with [plan]. Same matching as [plan], so
     * a split that [plan] would restore is not pending. [detachedOnly]: only splits of detached windows can be late (the main area is
     * restored as a whole before the first pass, and with "Reopen files on startup" off no group of it may survive, PERS-3), so the
     * splits of the main area are never pending.
     */
    fun pending(stored: StoredGroups, current: List<CurrentSplit>, detachedOnly: Boolean = false): StoredGroups {
        val matched = match(stored, current).keys
        val splits = stored.splits.filterIndexed { i, s -> i !in matched && (!detachedOnly || s.splitKey().container != SplitKey.MAIN) }
        val used = splits.flatMapTo(HashSet()) { s -> s.instances.map { it.groupId } }
        return StoredGroups(stored.groups.filter { it.id in used }, splits)
    }
}
