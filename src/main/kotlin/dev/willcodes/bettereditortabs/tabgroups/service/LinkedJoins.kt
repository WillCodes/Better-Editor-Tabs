package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupOrder
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.LinkedJoin
import dev.willcodes.bettereditortabs.tabgroups.model.groups.instanceStripOrder
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * Joining groups (CTX-2, SPLIT-2, SPLIT-5, INT-6, RULE-2) for [TabGroupsService], which delegates to it: a tab becomes the last member
 * of a group's instance ([joinInstance]), a file opened in a split joins the group it belongs to in another split ([joinLinked]), and
 * the entry for new tabs, which runs SPLIT-2 first and then the auto-group rules ([joinLinkedThenRules]). Also the ordering of an
 * instance that arrives from another split ([groupMovedIn]). Shares the service's [state]; [refresh], [arrange] and [leaveGroup] are the
 * service's own. EDT only.
 */
internal class LinkedJoins(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val saved: SavedGroups,
    private val rules: AutoGroupRules,
    private val refresh: (SplitHandle) -> Unit,
    private val arrange: (SplitHandle) -> Unit,
    private val leaveGroup: (SplitHandle, VirtualFile) -> Unit,
    private val isHost: () -> Boolean,
) {
    private val tabBar get() = project.service<TabBar>()

    /**
     * [file] becomes the last member of [groupId]'s instance in [split] (created, at the tab's position, if the split has none) and its
     * tab moves behind the instance's last tab (INV-2). A collapsed instance is expanded first (CTX-2: the user sees where the tab
     * went). [leave]: the file leaves its current group first (CTX-2); false for a file that has none (SPLIT-2).
     */
    fun joinInstance(split: SplitHandle, file: VirtualFile, groupId: String, leave: Boolean) {
        var target = state.instance(split, groupId)
        if (target != null && target.collapsed) {
            state.setCollapsed(split, groupId, false)
            refresh(split) // the group's tabs are visible again, so they take part in the order below
            target = state.instance(split, groupId)
        }
        if (leave) leaveGroup(split, file)
        if (target != null) tabBar.reorder(split, GroupOrder.orderForJoin(tabBar.visibleFiles(split), file, target.members))
        state.addToGroup(split, groupId, file)
        refresh(split)
        if (target == null) arrange(split)
    }

    /**
     * SPLIT-2: [files] were opened in [split] and are not members of a group there. A file that belongs to a group in another split
     * joins that group's instance here (created if the split has none); with different groups in different splits the one of the
     * most recently focused split wins ([LinkedJoin.pickGroup]). Pinned tabs (INV-3) and preview tabs (INV-4) stay out.
     */
    private fun joinLinked(split: SplitHandle, files: List<VirtualFile>) {
        if (!isHost()) return
        val others = state.splits().filter { it != split }
        if (others.isEmpty()) return
        val recent by lazy { tabBar.recentSplits() }
        val picks = ArrayList<LinkedJoin.Pick<VirtualFile>>()
        for (file in files) {
            if (!file.isValid) continue
            val candidates = others.mapNotNull { s -> state.groupOf(s, file)?.let { s to it.groupId } }
            if (candidates.isEmpty() || tabBar.openTabs(split, listOf(file)).isEmpty()) continue
            if (!LinkedJoin.eligible(tabBar.isPinned(split, file), tabBar.isPreview(split, file), state.groupOf(split, file) != null)) continue
            if (file !in tabBar.visibleFiles(split)) continue
            val groupId = LinkedJoin.pickGroup(candidates, recent) ?: continue
            val from = candidates.first { it.second == groupId }.first
            picks += LinkedJoin.Pick(file, groupId, state.instance(from, groupId)?.members?.indexOf(file) ?: Int.MAX_VALUE)
        }
        // several files at once (an unsplit): each group gets them at its end in the order they had where they came from
        for (pick in LinkedJoin.joinOrder(picks)) {
            if (state.groupOf(split, pick.file) == null) joinInstance(split, pick.file, pick.groupId, leave = false)
        }
    }

    /**
     * SPLIT-2 first (a file that belongs to a group in another split joins it: the linked join wins), then RULE-2 for what is still
     * ungrouped. The one entry for new and held-back tabs ([LateSplitRestore]) and for promoted preview tabs.
     */
    fun joinLinkedThenRules(split: SplitHandle, files: List<VirtualFile>) {
        joinLinked(split, files)
        rules.applyRules(split, files)
    }

    /**
     * INT-6, SPLIT-5: see [TabGroupsService.groupMovedIn]. The arrival is appended to the state's list, but [TabBarHost.groupsIn] must
     * list a split's groups in strip order (collapsed neighbours share a host tab and are drawn in list order), so the instances are put
     * in the order of their first members in the split's tab order: the tab bar has re-ordered the target's strip with everything
     * visible before it reports the arrival.
     */
    fun groupMovedIn(split: SplitHandle, group: GroupDecoration) {
        state.acceptInstance(split, group.id, group.name, GroupColors.colorOf(group.color), group.members, group.collapsed)
        val order = tabBar.logicalOrder(split)
        state.reorderInstances(split, instanceStripOrder(state.instancesIn(split).map { it.groupId to it.members.toList() }, order))
        saved.captureLater()
    }
}
