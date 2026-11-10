package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A group header as seen from outside `tabbar/`: enough to anchor a popup or dialog (§6) and to tell which group instance was hit.
 * [component] is the header's Swing component (showing, so `JBPopup.showUnderneathOf(component)` works); it is only valid while the
 * split shows the group.
 */
class HeaderRef(val split: SplitHandle, val groupId: String, val component: JComponent)

/**
 * The single interface the service layer implements to plug into the tab bar (Stage 2: `TabGroupsService`). The tab bar *pulls* state
 * from it ([groupsIn]) and *reports* gestures and events to it; everything else is the [TabBar] facade.
 *
 * Threading: every callback except [canReopenClosedGroup] runs on the EDT. [canReopenClosedGroup] is called from action updates,
 * which may run on a background thread, so it must be cheap and thread-safe.
 *
 * After [groupsChangedByDrag] and [groupMovedIn] the tab bar calls [TabBar.refresh] for the split itself, so an implementation only
 * has to update its own state in those callbacks; after any other callback that changes the groups of a split it calls
 * [TabBar.refresh] itself.
 */
interface TabBarHost {
    /** The groups to show in [split], in order. Pulled by [TabBar.refresh]; the answer must be a complete description of the split. */
    fun groupsIn(split: SplitHandle): List<GroupDecoration>

    // ---- header gestures (INT-1, INT-3, INT-4) --------------------------------------------------------------------------

    /** Left click on a header (INT-1: collapse / expand). */
    fun headerClicked(header: HeaderRef, event: MouseEvent) {}

    /** Right click on a header (INT-4: the group menu). */
    fun headerRightClicked(header: HeaderRef, event: MouseEvent) {}

    /** Middle click on a header (INT-3: delete group). */
    fun headerMiddleClicked(header: HeaderRef, event: MouseEvent) {}

    // ---- results of drag and drop (DRAG-*, INT-6) -------------------------------------------------------------------------

    /**
     * A tab or group drop changed membership and/or order in [split]; [groups] is the complete new state (collapsed flags unchanged).
     * A group left without a member is still in [groups] with an empty member list: the host decides (MEM-5).
     */
    fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>)

    /**
     * A whole group instance arrived from another split (INT-6, SPLIT-5). [group] is already merged with the split's instance of the
     * same group, if it had one. The source split has been told about its new groups through [groupsChangedByDrag] before.
     */
    fun groupMovedIn(split: SplitHandle, group: GroupDecoration)

    // ---- events ----------------------------------------------------------------------------------------------------------

    /** COLL-4: [file], a tab hidden by collapsed group [groupId], was selected (navigation, Ctrl+E, ...). Expand the group and [TabBar.refresh]. */
    fun hiddenTabSelected(split: SplitHandle, groupId: String, file: VirtualFile) {}

    /**
     * MEM-1: [file] no longer has a tab in [split] although the groups last applied there list it as a member. Not reported for the
     * source split when a group is moved to another split (the source was told about its new groups first).
     */
    fun tabClosed(split: SplitHandle, file: VirtualFile) {}

    /** A split appeared (also called once for every existing split when the host is attached). */
    fun splitAppeared(split: SplitHandle) {}

    /** A split was closed; its state can be dropped. */
    fun splitDisposed(split: SplitHandle) {}

    // ---- LIFE-5 --------------------------------------------------------------------------------------------------------------

    /** True if a closed group is available to reopen; keeps Reopen Closed Tab enabled when the platform history is empty. May run off the EDT. */
    fun canReopenClosedGroup(): Boolean = false

    /** Reopens the group and returns true, or returns false to let the built-in single-tab reopen run. */
    fun reopenClosedGroup(event: AnActionEvent): Boolean = false
}
