package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteStep
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupOrder
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.InstanceLayout
import dev.willcodes.bettereditortabs.tabgroups.model.groups.TabGroup
import dev.willcodes.bettereditortabs.tabgroups.model.groups.deleteStep
import dev.willcodes.bettereditortabs.tabgroups.model.groups.nextColor
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBarHost
import java.awt.event.MouseEvent
import java.util.UUID

/**
 * The project's tab groups (§2): the state ([GroupsState]) plus the operations (CTX-1..CTX-3 add and remove tabs, SPLIT-3 rename and
 * recolour, LIFE-1 ungroup, LIFE-3 delete), and the [TabBarHost] the [TabBar] reports to. Talks to the editor tab bars only through the
 * [TabBar] facade, and to popups and dialogs only through [TabGroupsUi] (implemented in `ui/`, which depends on this package, never
 * the other way round). EDT only.
 *
 * Attaching: the service attaches itself ([TabBar.host]) on the first group creation and stays attached for the session; detaching when
 * the groups run out would dispose the adapter that is calling it. Stage 5 (persistence) attaches at startup.
 */
@Service(Service.Level.PROJECT)
class TabGroupsService(private val project: Project) : Disposable, TabBarHost {
    private val state = GroupsState<SplitHandle, VirtualFile> { UUID.randomUUID().toString() }
    private val tabBar get() = project.service<TabBar>()
    private val settings get() = TabGroupsSettings.getInstance()

    // ---- queries -----------------------------------------------------------------------------------------------------------

    /** Every group of the project, in creation order (CTX-2 lists them in this order). */
    fun groups(): List<TabGroup> = state.groups.toList()

    fun group(groupId: String): TabGroup? = state.group(groupId)

    /** The group [file] belongs to in [split], if any. */
    fun groupOf(split: SplitHandle, file: VirtualFile): TabGroup? = state.groupOf(split, file)?.let { state.group(it.groupId) }

    /** The theme-aware colour of [group]. */
    fun colorOf(group: TabGroup): JBColor = GroupColors.jbColor(group.color)

    /** The name to show in menus: the group's name, or "Unnamed group (Blue)" (CTX-2). */
    fun displayName(group: TabGroup): String =
        group.name.ifEmpty { BetterEditorTabsBundle.message("group.unnamed", BetterEditorTabsBundle.message(group.color.messageKey)) }

    // ---- operations (each ends with a refresh of the split) -------------------------------------------------------------

    /**
     * CTX-1: a new group containing [file] only, at the tab's position, with the next colour (COL-1). Null (nothing done) if the tab is
     * pinned (INV-3) or not visible in [split]. A preview tab becomes permanent (MEM-7, INV-4). If the tab is in another group of the
     * split it first moves to just after that group, so the old group stays contiguous (INV-2).
     */
    fun createGroup(split: SplitHandle, file: VirtualFile): TabGroup? {
        if (!prepare(split, file)) return null
        leaveGroup(split, file)
        val color = nextColor(state.groups.map { it.color }, settings.fixedNewGroupColor)
        val group = state.createGroup(split, file, color)
        tabBar.refresh(split)
        arrange(split)
        return group
    }

    /**
     * CTX-2: [file] becomes the last member of group [groupId] in [split]; the tab moves to just after the group's last tab (INV-2). Without
     * an instance in the split a new one is created at the tab's position. A collapsed target is expanded first (decision: the user sees
     * where the tab went). Picking the tab's own group moves it to the end of that group. Refused like [createGroup]; returns whether
     * anything was done.
     */
    fun addToGroup(split: SplitHandle, file: VirtualFile, groupId: String): Boolean {
        if (state.group(groupId) == null || !prepare(split, file)) return false
        var target = state.instance(split, groupId)
        if (target != null && file in target) {
            // CTX-2 on the tab's own group: it moves to the end of the group (nothing to do if it already is the last tab).
            if (target.members.last() == file) return false
            leaveGroup(split, file)
            state.addToGroup(split, groupId, file)
            tabBar.refresh(split)
            return true
        }
        if (target != null && target.collapsed) {
            state.setCollapsed(split, groupId, false)
            tabBar.refresh(split) // the group's tabs are visible again, so they take part in the order below
            target = state.instance(split, groupId)
        }
        leaveGroup(split, file)
        if (target != null) tabBar.reorder(split, GroupOrder.orderForJoin(tabBar.visibleFiles(split), file, target.members))
        state.addToGroup(split, groupId, file)
        tabBar.refresh(split)
        if (target == null) arrange(split)
        return true
    }

    /** CTX-3: [file] leaves its group in [split] and moves to just after the group. */
    fun removeFromGroup(split: SplitHandle, file: VirtualFile) {
        if (state.groupOf(split, file) == null) return
        leaveGroup(split, file)
        state.removeFromGroup(split, file)
        tabBar.refresh(split)
    }

    /** Renames [groupId] (empty = unnamed) and refreshes every split that shows it (SPLIT-3). */
    fun rename(groupId: String, name: String) {
        val group = state.group(groupId) ?: return
        if (group.name == name) return
        group.name = name
        refreshSplitsOf(groupId)
    }

    /** Changes the colour of [groupId] and refreshes every split that shows it (SPLIT-3). */
    fun recolor(groupId: String, color: GroupColor) {
        val group = state.group(groupId) ?: return
        if (group.color == color) return
        group.color = color
        refreshSplitsOf(groupId)
    }

    /**
     * LIFE-1: removes [split]'s instance of [groupId]. Its tabs stay open where they are, ungrouped; other splits are unaffected. The
     * tabs of a collapsed group were hidden: the refresh brings them back at the header's place ([TabBar.refresh] shows what is no
     * longer collapsed, after the tab the group sat behind).
     */
    fun ungroup(split: SplitHandle, groupId: String) {
        if (state.instance(split, groupId) == null) return
        state.removeInstance(split, groupId)
        tabBar.refresh(split)
    }

    /**
     * LIFE-3: Delete Group for [split]'s instance of [groupId]. A non-empty group asks first (LIFE-3b) unless the setting of [trigger]
     * is off (SET-4 for [DeleteTrigger.MENU], SET-5 for [DeleteTrigger.MIDDLE_CLICK]); a group without open tabs goes at once (LIFE-3c).
     * "Don't ask again" switches off the setting of [trigger] only.
     */
    fun requestDelete(split: SplitHandle, groupId: String, trigger: DeleteTrigger) {
        val group = state.group(groupId) ?: return
        val instance = state.instance(split, groupId) ?: return
        // Stage 7 (LIFE-3a): with linked instances in other splits a Move / Close / Cancel dialog comes first. Until then the instances
        // of other splits are left alone.
        val tabCount = tabBar.openTabs(split, instance.members).size
        if (deleteStep(tabCount, settings.confirmsDelete(trigger)) == DeleteStep.CONFIRM) {
            when (TabGroupsUi.getInstance(project).confirmDelete(displayName(group), tabCount)) {
                DeleteAnswer.CANCEL -> return
                DeleteAnswer.DELETE_AND_STOP_ASKING -> settings.stopConfirmingDelete(trigger)
                DeleteAnswer.DELETE -> {}
            }
        }
        deleteInstance(split, groupId)
    }

    /**
     * Removes the instance, then closes its open tabs. The state is re-read here because the confirmation is modal and may have
     * let things change. The instance goes first and the split is refreshed, so no tab is still hidden while it closes and the tab bar
     * no longer lists the files (MEM-1 [tabClosed] is not reported for them).
     */
    private fun deleteInstance(split: SplitHandle, groupId: String) {
        val instance = state.instance(split, groupId) ?: return
        val tabs = tabBar.openTabs(split, instance.members)
        // Stage 10 (LIFE-5): record the deleted group (name, colour, tab paths) here, before its tabs close.
        state.removeInstance(split, groupId)
        tabBar.refresh(split)
        tabBar.closeTabs(split, tabs)
    }

    private fun refreshSplitsOf(groupId: String) {
        for (split in state.splits()) if (state.instance(split, groupId) != null) tabBar.refresh(split)
    }

    /** Pinned tabs are refused (INV-3), so are tabs that are not showing; a preview tab becomes permanent (MEM-7). Attaches the host. */
    private fun prepare(split: SplitHandle, file: VirtualFile): Boolean {
        if (tabBar.isPinned(split, file) || file !in tabBar.visibleFiles(split)) return false
        if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
        attach()
        return true
    }

    /** Moves [file] to just after its current group in [split] (CTX-3, and before it joins another group), so that group stays contiguous. */
    private fun leaveGroup(split: SplitHandle, file: VirtualFile) {
        val old = state.groupOf(split, file) ?: return
        tabBar.reorder(split, GroupOrder.orderForLeave(tabBar.visibleFiles(split), file, old.members))
    }

    /**
     * A menu operation added an instance to [split] (appended to the state's list). [TabBarHost.groupsIn] must list the split's groups
     * in strip order, because collapsed groups next to each other share a host tab and are drawn in list order, so the instances are
     * re-sorted to the order of the headers in the strip ([TabBar.groupOrder]) and the split is refreshed again if that changed it.
     * Drag results need none of this: they report the new order themselves.
     */
    private fun arrange(split: SplitHandle) {
        val order = tabBar.groupOrder(split)
        if (order.isEmpty() || state.instancesIn(split).map { it.groupId } == order) return
        state.reorderInstances(split, order)
        tabBar.refresh(split)
    }

    private fun attach() {
        if (tabBar.host === this) return
        tabBar.style = StripStyle(settings.opacity, settings.nameLimit)
        tabBar.host = this
    }

    // ---- TabBarHost ----------------------------------------------------------------------------------------------------------

    override fun groupsIn(split: SplitHandle): List<GroupDecoration> = state.instancesIn(split).mapNotNull { inst ->
        val group = state.group(inst.groupId) ?: return@mapNotNull null
        GroupDecoration(group.id, group.name, colorOf(group), inst.members.toList(), inst.collapsed)
    }

    /** DRAG-*: the tab bar refreshes the split itself afterwards. MEM-7: a preview tab dropped into a group becomes permanent. */
    override fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>) {
        state.replaceSplit(split, groups.map { InstanceLayout(it.id, it.members, it.collapsed) })
        for (group in groups) for (file in group.members) if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
    }

    /** INT-6, SPLIT-5: the tab bar refreshes the split itself afterwards. */
    override fun groupMovedIn(split: SplitHandle, group: GroupDecoration) {
        state.acceptInstance(split, group.id, group.name, GroupColors.colorOf(group.color), group.members, group.collapsed)
    }

    /** MEM-1. */
    override fun tabClosed(split: SplitHandle, file: VirtualFile) = removeAfterEvent(split, file)

    /** MEM-3: the platform moves the pinned tab itself (see [TabBarHost.tabPinned]). */
    override fun tabPinned(split: SplitHandle, file: VirtualFile) = removeAfterEvent(split, file)

    private fun removeAfterEvent(split: SplitHandle, file: VirtualFile) {
        state.removeFromGroup(split, file)
        tabBar.refresh(split)
    }

    /** COLL-4, unless SET-6 (auto-expand on navigate) is off. */
    override fun hiddenTabSelected(split: SplitHandle, groupId: String, file: VirtualFile) {
        if (!settings.state.autoExpandOnNavigate) return
        state.setCollapsed(split, groupId, false)
        tabBar.refresh(split)
    }

    /** INT-1: collapse / expand. */
    override fun headerClicked(header: HeaderRef, event: MouseEvent) {
        val instance = state.instance(header.split, header.groupId) ?: return
        state.setCollapsed(header.split, header.groupId, !instance.collapsed)
        tabBar.refresh(header.split)
    }

    /** INT-3: Delete Group, with the middle-click confirmation setting (SET-5). */
    override fun headerMiddleClicked(header: HeaderRef, event: MouseEvent) {
        requestDelete(header.split, header.groupId, DeleteTrigger.MIDDLE_CLICK)
    }

    /** INT-4: the group popup. */
    override fun headerRightClicked(header: HeaderRef, event: MouseEvent) {
        TabGroupsUi.getInstance(project).showGroupPopup(header)
    }

    override fun splitDisposed(split: SplitHandle) {
        state.splitRemoved(split)
    }

    override fun dispose() {
        // Only detach on the EDT, and only if the tab bar still reports to this service.
        if (!ApplicationManager.getApplication().isDispatchThread) return
        val bar = project.getServiceIfCreated(TabBar::class.java) ?: return
        if (bar.host === this) bar.host = null
    }
}
