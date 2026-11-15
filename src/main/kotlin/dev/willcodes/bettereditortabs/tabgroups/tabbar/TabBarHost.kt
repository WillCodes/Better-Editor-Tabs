package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorAreaDrop
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A group header as seen from outside `tabbar/`: enough to anchor a popup or dialog (§6) and to tell which group instance was hit.
 * [component] is the header's Swing component (showing, so a popup can be placed relative to it); it is only valid while the
 * split shows the group. [pillBounds] is the pill as painted, in [component]'s coordinates, taken when the ref was made: the
 * component is a little wider than the pill (a gap on each side), so a popup aligned to the pill's leading edge (§6) uses this.
 * [stripSide] tells on which side of the editor the strip stands, which decides where a popup opens (§6). Only `tabbar/` creates refs.
 */
class HeaderRef internal constructor(
    val split: SplitHandle,
    val groupId: String,
    val component: JComponent,
    val pillBounds: Rectangle = Rectangle(0, 0, component.width, component.height),
    /** The side of the editor area the strip is on when the ref was made (the tab placement; NONE while the tabs are hidden). */
    val stripSide: EditorAreaDrop.StripSide = EditorAreaDrop.StripSide.TOP,
)

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

    /**
     * CTX-5 (user request 2026-11-14): a right click on the tab strip of [split] where there is no tab and no header (the free part of
     * the strip, also a strip that shows only pills). [component] and [point] (in its coordinates) are where a context menu opens.
     */
    fun stripRightClicked(split: SplitHandle, component: JComponent, point: java.awt.Point) {}

    /**
     * INT-5: the pointer entered ([hovering] true) or left (false) the header. A press on the header (click or the start of a drag)
     * also reports false. Called for expanded and collapsed groups alike; the host decides what to show (the tab list of a collapsed
     * group, after a delay).
     */
    fun headerHoverChanged(header: HeaderRef, hovering: Boolean) {}

    // ---- results of drag and drop (DRAG-*, INT-6) -------------------------------------------------------------------------

    /**
     * A tab or group drop changed membership and/or order in [split]; [groups] is the complete new state (collapsed flags unchanged).
     * A group left without a member is still in [groups] with an empty member list: the host decides (MEM-5).
     */
    fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>)

    /**
     * IDE-2: the strip re-sorted itself alphabetically (a tab opened or was renamed, a group was renamed, sorting was switched on or
     * off), so the order of [split]'s groups and of their members changed; [groups] is the complete new state (membership and
     * collapsed flags unchanged). Like [groupsChangedByDrag], the tab bar refreshes the split itself afterwards (never animated).
     */
    fun groupsReordered(split: SplitHandle, groups: List<GroupDecoration>) = groupsChangedByDrag(split, groups)

    /**
     * A whole group instance arrived from another split (INT-6, SPLIT-5). [group] is already merged with the split's instance of the
     * same group, if it had one. The source split has been told about its new groups through [groupsChangedByDrag] before.
     */
    fun groupMovedIn(split: SplitHandle, group: GroupDecoration)

    // ---- events ----------------------------------------------------------------------------------------------------------

    /** COLL-4: [file], a tab hidden by collapsed group [groupId], was selected (navigation, Ctrl+E, ...). Expand the group and [TabBar.refresh]. */
    fun hiddenTabSelected(split: SplitHandle, groupId: String, file: VirtualFile) {}

    /**
     * COLL-6: [file], a tab hidden by collapsed group [groupId], was picked in the strip's overflow ("more") list. An explicit request
     * like picking it in the hover list (INT-5): expand the group whatever SET-6 says, [TabBar.refresh] and select the tab. The tab bar
     * selects nothing itself. Default: treated like a navigation ([hiddenTabSelected]).
     */
    fun collapsedTabChosen(split: SplitHandle, groupId: String, file: VirtualFile) = hiddenTabSelected(split, groupId, file)

    /**
     * MEM-1: [file] no longer has a tab in [split] although the groups last applied there list it as a member. Not reported for the
     * source split when a group is moved to another split (the source was told about its new groups first).
     */
    fun tabClosed(split: SplitHandle, file: VirtualFile) {}

    /**
     * MEM-3: [file] has just been pinned in [split] although the groups last applied there list it as a member (a pin in another split,
     * or of a file the groups of this split do not list, is not reported; unpinning is not reported). The platform has already changed
     * the pin state when this is called. The host is expected to remove the file from its group and call [TabBar.refresh].
     *
     * Position of the pinned tab: the platform moves it by itself. `JBTabsImpl.getVisibleInfos` (called by every layout and by
     * [TabBar.visibleFiles]) groups pinned tabs to the front of the strip while the advanced setting `editor.keep.pinned.tabs.on.left`
     * is on, and `EditorWindow.setFilePinned` does a layout right after publishing the event that leads here, so the tab is at the front
     * once that layout has run (not yet while this callback runs: the move happens on the next `getVisibleInfos` call).
     * The host therefore need not reorder the tab; it should not rely on any other order of the unpinned tabs either: the platform's
     * grouping swaps a pinned tab with the first unpinned one, so the unpinned tabs may change relative order. With the setting off a
     * pinned tab stays where it is.
     */
    fun tabPinned(split: SplitHandle, file: VirtualFile) {}

    /**
     * SPLIT-2: [files] have just got a tab in [split] (opened there by the user or by the platform: Split Right, Open in Split, a copy,
     * a file merged by an unsplit), and [split] is not showing them as group members. The host may make them members of a group; it
     * must then call [TabBar.refresh] for [split] itself. The tab bar applies MEM-2 (a new tab never lands inside a group) afterwards,
     * only to the files that are still not members, reading the groups again. Not reported: the tabs of the splits that exist when the
     * host attaches, tabs that arrive while a drag runs, and the files our own drag and group-move code opens in a target split (they
     * already got their membership from the drop). A split that appears later reports the files it starts with, in the first call
     * after the split is announced with [splitAppeared].
     */
    fun tabsOpened(split: SplitHandle, files: List<VirtualFile>) {}

    /**
     * RULE-2: the preview tab of [file] in [split] has just become a normal tab (a double click on it, an edit in it, opening the file
     * again for good, ...). Not reported for [TabBar.makePermanent] (the host's own call), for tabs that were never preview tabs, or
     * while a drag runs. The host may make the file a member of a group; it then calls [TabBar.refresh] itself. EDT.
     */
    fun tabMadePermanent(split: SplitHandle, file: VirtualFile) {}

    /** A split appeared (also called once for every existing split when the host is attached). */
    fun splitAppeared(split: SplitHandle) {}

    /** A split was closed; its state can be dropped. */
    fun splitDisposed(split: SplitHandle) {}

    /**
     * SPLIT-6: the user is closing, or has closed, the temporary split [split], a split that holds nothing but empty groups ([groups],
     * the groups last applied there, all of them without a tab) and is kept open by a hidden placeholder tab. Asked once per split
     * (Unsplit, Close All in it, closing the detached window, anything that closes the placeholder or disposes the split), never for a
     * split the tab bar ends itself (its last group is gone, a real tab arrived) and never while the project closes.
     *
     * The host moves each group to the split used last that has tabs ([TabBar.recentSplits]; the end of that strip) or deletes it
     * when there is none, and says so ([TabBar.refresh] the target itself). Usually called before the split is disposed, with its
     * strip still in place; when the platform gives no warning (an unsplit merges the split away, its window is closed) it is called
     * after the disposal, and [split] is no longer a live split: the host must work on its own state only. EDT.
     */
    fun temporarySplitClosing(split: SplitHandle, groups: List<GroupDecoration>) {}

    // ---- LIFE-5 --------------------------------------------------------------------------------------------------------------

    /**
     * True if a deleted group is recorded in some split (not necessarily the one Reopen Closed Tab will act on); keeps Reopen Closed Tab
     * enabled when the platform history is empty. May run off the EDT.
     */
    fun canReopenClosedGroup(): Boolean = false

    /**
     * Reopen Closed Tab: brings back the group that was deleted last in the split the action acts on ([TabBar.reopenSplit]) and returns
     * true, if no tab was closed there since ([TabBar.isMostRecentClose]); otherwise returns false and the built-in single-tab reopen
     * runs (a tab closed after the delete is reopened first, then the group). EDT.
     */
    fun reopenClosedGroup(event: AnActionEvent): Boolean = false
}
