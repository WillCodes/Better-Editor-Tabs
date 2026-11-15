package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupCommandRules
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupInstance
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupSpan
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.InstanceState
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * The commands that act on the "current group" (the group of the context tab, see `actions/TabContext`) or on all groups of a
 * split, for [TabGroupsService], which delegates to it: Toggle Collapse of Current Group, Collapse Other Groups, Expand All Groups,
 * Move Tab to Next / Previous Group, Select Next / Previous Tab in Group. The decisions are in [GroupCommandRules]. Every command has a
 * `can...` query for the actions' `update` (cheap, no side effects). Shares the service's [state]; [headers] does the collapsing and the
 * selecting, [refresh] and [addToGroup] are the service's own. EDT only.
 *
 * Toggle Collapse: collapsing moves the selection out of the group, so the context tab is no longer
 * a member and the action could never expand the group again. Therefore an ungrouped context tab targets the group this action
 * collapsed last in that split, if it still exists there and is still collapsed ([lastCollapsed], session memory, dropped with the split).
 */
internal class GroupCommands(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val headers: HeaderInteractions,
    private val refresh: (SplitHandle) -> Unit,
    private val addToGroup: (SplitHandle, VirtualFile, String) -> Boolean,
) {
    private val tabBar get() = project.service<TabBar>()

    /** The group Toggle Collapse collapsed last, per split. */
    private val lastCollapsed = HashMap<SplitHandle, String>()

    /** [split] is gone: forget what it remembered. */
    fun splitGone(split: SplitHandle) {
        lastCollapsed.remove(split)
    }

    // ---- toggle ------------------------------------------------------------------------------------------------------------

    /** The group Toggle Collapse would act on for the context tab [file] (null: no usable tab); null if there is none (the action is disabled). */
    fun toggleTarget(split: SplitHandle, file: VirtualFile?): String? {
        val remembered = lastCollapsed[split]
        val rememberedCollapsed = remembered != null && state.instance(split, remembered)?.collapsed == true
        return GroupCommandRules.toggleTarget(file?.let { groupIdOf(split, it) }, remembered, rememberedCollapsed)
    }

    /** Toggle Collapse of Current Group; false if there was nothing to toggle. */
    fun toggleCurrent(split: SplitHandle, file: VirtualFile?): Boolean {
        val id = toggleTarget(split, file) ?: return false
        val collapsed = headers.toggleCollapsed(split, id) ?: return false
        if (collapsed) lastCollapsed[split] = id else if (lastCollapsed[split] == id) lastCollapsed.remove(split)
        return true
    }

    // ---- collapse others, expand all -----------------------------------------------------------------------------------------

    private fun collapseOthersTargets(split: SplitHandle, file: VirtualFile?): List<String> =
        if (file == null) emptyList() else GroupCommandRules.collapseOthers(states(split), groupIdOf(split, file))

    /** Collapse Other Groups is enabled for a usable tab while some other group is expanded or partly shown. */
    fun canCollapseOthers(split: SplitHandle, file: VirtualFile?): Boolean = collapseOthersTargets(split, file).isNotEmpty()

    /** Collapse Other Groups: one refresh (animated when animations are on). Returns whether anything changed. */
    fun collapseOthers(split: SplitHandle, file: VirtualFile?): Boolean {
        val targets = collapseOthersTargets(split, file)
        if (targets.isEmpty()) return false
        for (id in targets) state.setCollapsed(split, id, true)
        refresh(split)
        return true
    }

    fun canExpandAll(split: SplitHandle): Boolean = GroupCommandRules.expandAll(states(split)).isNotEmpty()

    /** Expand All Groups: every collapsed or partly shown group of [split] expands fully, in one refresh. */
    fun expandAll(split: SplitHandle): Boolean {
        val targets = GroupCommandRules.expandAll(states(split))
        if (targets.isEmpty()) return false
        for (id in targets) state.setCollapsed(split, id, false)
        refresh(split)
        return true
    }

    private fun states(split: SplitHandle): List<InstanceState> = state.instancesIn(split).map {
        InstanceState(it.groupId, it.collapsed, it.peek != null, tabBar.openTabs(split, it.members).size)
    }

    // ---- move to the adjacent group --------------------------------------------------------------------------------------------

    /** The id of the group [file] would move to: next or previous in strip order; null for none, a pinned tab, or a tab that is not open. */
    fun adjacentGroup(split: SplitHandle, file: VirtualFile, next: Boolean): String? {
        if (tabBar.isPinned(split, file)) return null
        val logical = tabBar.logicalOrder(split)
        val tabIndex = logical.indexOf(file)
        if (tabIndex < 0) return null
        val strip = tabBar.groupOrder(split).mapNotNull { id ->
            val instance = state.instance(split, id) ?: return@mapNotNull null
            val first = tabBar.headerIndex(split, id) ?: return@mapNotNull null
            GroupSpan(id, first, tabBar.openTabs(split, instance.members).size)
        }
        val own = groupIdOf(split, file)
        return if (next) GroupCommandRules.nextGroup(strip, own, tabIndex) else GroupCommandRules.previousGroup(strip, own, tabIndex)
    }

    /** Move Tab to Next / Previous Group: like picking that group from the tab's context menu ([addToGroup]: end of the group, collapsed target expands, a preview tab becomes permanent). */
    fun moveToAdjacent(split: SplitHandle, file: VirtualFile, next: Boolean): Boolean {
        val target = adjacentGroup(split, file, next) ?: return false
        return addToGroup(split, file, target)
    }

    // ---- select within the group -----------------------------------------------------------------------------------------------

    /** The instance of [file]'s group and its open members in logical order; null for an ungrouped tab. */
    private fun openMembers(split: SplitHandle, file: VirtualFile): Pair<GroupInstance<VirtualFile>, List<VirtualFile>>? {
        val instance = state.groupOf(split, file) ?: return null
        return instance to tabBar.logicalOrder(split).filter { it in instance }
    }

    /** Select Next / Previous Tab in Group is enabled for a group with at least two open tabs. */
    fun canSelectInGroup(split: SplitHandle, file: VirtualFile): Boolean = (openMembers(split, file)?.second?.size ?: 0) >= 2

    /**
     * Select Next / Previous Tab in Group: selects and focuses the next or previous open member, cycling at the ends. A member
     * hidden by a collapsed group is shown partly, as when picking it in the tab list ([HeaderInteractions.openFromTabList]).
     */
    fun selectInGroup(split: SplitHandle, file: VirtualFile, next: Boolean): Boolean {
        val (instance, members) = openMembers(split, file) ?: return false
        val target = GroupCommandRules.cycle(members, file, if (next) 1 else -1) ?: return false
        headers.openFromTabList(split, instance.groupId, target)
        return true
    }

    private fun groupIdOf(split: SplitHandle, file: VirtualFile): String? = state.groupOf(split, file)?.groupId
}
