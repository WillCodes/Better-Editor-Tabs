package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.persist.CurrentSplit
import dev.willcodes.bettereditortabs.tabgroups.model.persist.RestorePlan
import dev.willcodes.bettereditortabs.tabgroups.model.persist.RestorePlanner
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroups
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredInstance
import dev.willcodes.bettereditortabs.tabgroups.model.persist.colorFromName
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo

/**
 * The startup restore for [TabGroupsService], which delegates to it: [restore] matches the stored splits
 * to the live ones once, and [applyPlan] puts a plan's order and instances into the state (also used by [LateSplitRestore] for detached
 * windows that appear later). Shares the service's [state]; [refresh] and [arrange] are the service's own (the Boolean is `animate`),
 * [attach] attaches the host, [startLate] hands the stored splits nobody claimed to [LateSplitRestore.start]. EDT only.
 */
internal class GroupsRestore(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val saved: SavedGroups,
    private val refresh: (SplitHandle, Boolean) -> Unit,
    private val arrange: (SplitHandle, Boolean) -> Unit,
    private val attach: () -> Unit,
    private val startLate: (List<SplitInfo>, StoredGroups) -> Unit,
) {
    private val tabBar get() = project.service<TabBar>()

    /** See [TabGroupsService.restore]. */
    fun restore(splits: List<SplitInfo>) {
        if (saved.restored) return
        val stored = saved.stored()
        saved.markRestored()
        val current = splits.map { s -> CurrentSplit(s.key, s.files.map { it.url }) }
        val plan = RestorePlanner.plan(stored, current) // the stash is not part of it: it survives a restart whatever reopened
        val pending = RestorePlanner.pending(stored, current, detachedOnly = true)
        if (plan.splits.isNotEmpty() || pending.splits.isNotEmpty()) attach()
        startLate(splits, pending)
        applyPlan(plan, splits)
        saved.capture()
    }

    /** Applies a [plan] made for [splits] (the index of a planned split is its place in the list): order, instances, refresh without animation. */
    fun applyPlan(plan: RestorePlan, splits: List<SplitInfo>) {
        val groups = plan.groups.associateBy { it.id }
        for (planned in plan.splits) {
            val info = splits[planned.current]
            val split = info.handle
            if (state.instancesIn(split).isNotEmpty()) continue
            val byUrl = info.files.associateBy { it.url }
            tabBar.reorder(split, planned.order.mapNotNull { byUrl[it] })
            // Before the groups arrive, so a split the plugin sorts starts from the manual order it had, not the sorted one
            if (planned.manualOrder.isNotEmpty()) tabBar.setManualOrder(split, planned.manualOrder.mapNotNull { byUrl[it] })
            val empties = ArrayList<StoredInstance>()
            for (inst in planned.instances) {
                val group = groups[inst.groupId] ?: continue
                val members = inst.members.mapNotNull { byUrl[it] }
                // An instance stored empty comes back empty (kept as kept) and is placed once the split is refreshed
                if (inst.members.isEmpty()) empties += inst
                state.acceptInstance(split, group.id, group.name, colorFromName(group.color), members, inst.collapsed, group.rule?.toRule(), inst.members.isEmpty())
                // A restored preview tab cannot be a member.
                for (file in members) if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
            }
            refresh(split, false)
            for (inst in empties) tabBar.placeEmptyGroup(split, inst.groupId, inst.place ?: Int.MAX_VALUE)
            arrange(split, false)
        }
    }
}
