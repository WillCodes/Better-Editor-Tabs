package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * What the user does with a group header, and the expand / collapse decisions (INT-1, INT-3, INT-4, INT-5, COLL-4, COLL-6, COLL-8) for
 * [TabGroupsService], which delegates to it: click ([headerClicked]), middle click, right click, hover, picking a tab from the list
 * ([openFromTabList]) and navigating to a tab of a collapsed group ([hiddenTabSelected]). Shares the service's [state]; [refresh] is the
 * service's own, [requestDelete] is [GroupDeletes.requestDelete]. EDT only.
 */
internal class HeaderInteractions(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val refresh: (SplitHandle) -> Unit,
    private val requestDelete: (SplitHandle, String, DeleteTrigger) -> Unit,
) {
    private val tabBar get() = project.service<TabBar>()
    private val settings get() = TabGroupsSettings.getInstance()

    /**
     * COLL-4 as changed by COLL-8 (partial expand), unless SET-6 (auto-expand on navigate) is off: navigating to [file] of a collapsed
     * group shows only that tab beside the header (with the "+N" count of the others); navigating to another of its tabs moves the
     * partial expand there. The group stays collapsed: a click on the header shows the rest ([headerClicked]).
     */
    fun hiddenTabSelected(split: SplitHandle, groupId: String, file: VirtualFile) {
        if (!settings.state.autoExpandOnNavigate) return
        showPartially(split, groupId, file)
    }

    /**
     * COLL-8 (fix round 1, user request): a partial expand lasts while its tab is the split's selected tab. When the user selects another
     * tab of the split, every partly shown group that does not contain it collapses fully again (animated, COLL-7). The group that
     * contains [file] is left to [hiddenTabSelected], which moves its partial expand there (no collapse-then-expand in between).
     * Focus moving to another split does not count: the tab is still that split's selected tab.
     */
    fun tabSelected(split: SplitHandle, file: VirtualFile) {
        val ending = state.instancesIn(split).filter { it.collapsed && it.peek != null && it.peek != file && file !in it }
        if (ending.isEmpty()) return
        for (inst in ending) state.setCollapsed(split, inst.groupId, true)
        refresh(split)
    }

    /** COLL-8: [file] shown beside the header of the collapsed group [groupId]; an expanded group is left alone. */
    private fun showPartially(split: SplitHandle, groupId: String, file: VirtualFile) {
        val instance = state.instance(split, groupId) ?: return
        if (!instance.collapsed || instance.peek == file) return
        if (state.showPartially(split, groupId, file)) refresh(split)
    }

    /**
     * INT-5: the pointer rests on (or left) a header. The tab list is offered only for a collapsed group with open tabs, and only while
     * SET-7 is on; leaving or pressing the header always ends the hover.
     */
    fun headerHoverChanged(header: HeaderRef, hovering: Boolean) {
        val ui = TabGroupsUi.getInstance(project)
        if (!hovering) {
            ui.hoverEnded(header)
            return
        }
        val instance = state.instance(header.split, header.groupId) ?: return
        if (!instance.collapsed || !settings.state.showHoverList) return
        val files = tabBar.openTabs(header.split, instance.members)
        if (files.isNotEmpty()) ui.showTabListOnHover(header, files)
    }

    /**
     * INT-5 (and COLL-6): [file] was picked in the hover list (or the overflow list) of group [groupId] in [split]. A collapsed group
     * shows that tab partially (COLL-8, animated, COLL-7) and the tab is selected and focused. Done even with SET-6 off: picking from
     * the list is an explicit request, not a navigation.
     */
    fun openFromTabList(split: SplitHandle, groupId: String, file: VirtualFile) {
        state.instance(split, groupId) ?: return
        showPartially(split, groupId, file)
        tabBar.selectTab(split, file, true)
    }

    /**
     * INT-1: collapse / expand. COLL-8: a partially shown group expands fully (the count goes); the next click collapses it. Also the
     * click on the "+N" count, which the tab bar reports as a click on its group's header.
     */
    fun headerClicked(header: HeaderRef) {
        toggleCollapsed(header.split, header.groupId)
    }

    /**
     * INT-1, §13 Toggle Collapse of Current Group: a collapsed group, with or without a partial expand (COLL-8), expands fully; an
     * expanded one collapses (COLL-2 selection move and COLL-7 animation happen in the refresh, SET-10). Returns whether the group is
     * collapsed now, null if [split] has no such instance.
     */
    fun toggleCollapsed(split: SplitHandle, groupId: String): Boolean? {
        val instance = state.instance(split, groupId) ?: return null
        val expand = instance.collapsed
        state.setCollapsed(split, groupId, !expand)
        refresh(split)
        return !expand
    }

    /** INT-3: Delete Group, with the middle-click confirmation setting (SET-5). */
    fun headerMiddleClicked(header: HeaderRef) {
        requestDelete(header.split, header.groupId, DeleteTrigger.MIDDLE_CLICK)
    }

    /** INT-4: the group popup. */
    fun headerRightClicked(header: HeaderRef) {
        TabGroupsUi.getInstance(project).showGroupPopup(header)
    }
}
