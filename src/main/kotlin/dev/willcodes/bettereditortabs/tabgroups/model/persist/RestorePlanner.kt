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
        val persisted = stored.splits.map { s -> SplitSnapshot(s.splitKey(), (s.order + s.instances.flatMap { it.members }).toSet()) }
        val live = current.map { SplitSnapshot(it.key, it.files.toSet()) }
        val matches = SplitMatcher.match(persisted, live)
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
}
