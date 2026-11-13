package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.SplitGroups
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.TabBarDnd
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabPlacement
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.GroupHeader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripEvents
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.event.MouseEvent

/**
 * Everything the tab bar keeps for one split while a host is attached: the strip adapter, the drag layer (tab and group drag and drop)
 * and MEM-2 placement, plus the groups last applied to the split and an immutable index of them for lookups from any thread.
 * Created by [TabBar] when a split appears, disposed with it.
 *
 * Bridges the sub-packages to the host: strip events and drag results come in through [StripEvents] and [SplitGroups] and leave through
 * [TabBarHost]. EDT only, except [groupIdOf].
 */
internal class SplitBar(
    val split: SplitHandle,
    private val tabBar: TabBar,
    parent: Disposable,
    /** The split appeared after the host attached: its first files count as opened in it (SPLIT-2, see [TabPlacement]). */
    reportInitial: Boolean = false,
) : Disposable, StripEvents, SplitGroups {
    val adapter = TabBarAdapter(split.window, this) { tabBar.style }

    /** The groups last applied to the split (what the strip shows). */
    @Volatile
    var groups: List<GroupDecoration> = emptyList()
        private set

    /** File -> id of its group, built from [groups]; swapped as a whole so that any thread may read it. */
    @Volatile
    private var index: Map<VirtualFile, String> = emptyMap()

    val dnd = TabBarDnd(adapter, this) { tabBar.dragLayers() }.also { d -> adapter.dragActive = { d.isDragActive } }

    /** A report of newly opened tabs ([TabBarHost.tabsOpened]) is queued for this split. */
    val hasPendingReport: Boolean get() = placement.hasPending

    private val placement: TabPlacement

    init {
        Disposer.register(parent, this)
        placement = TabPlacement(
            adapter, { groups }, { dnd.isTabDragActive }, { files -> tabBar.host?.tabsOpened(split, files) }, reportInitial, dnd,
        )
        dnd.acknowledgeOpened = placement::acknowledge
        adapter.events = this
    }

    /**
     * Applies [newGroups] to the strip and refreshes the index; [animate]: collapse flips are animated (COLL-7). [expanded]: a group
     * that was dragged as its collapsed header and comes back expanded (INT-6): it is measured as flipping from collapsed, so it plays
     * the expand animation if [animate] allows it, whether it was in this split before (the drop in its own strip) or not (a move in).
     */
    fun apply(newGroups: List<GroupDecoration>, animate: Boolean, expanded: String? = null) {
        // What the host applied before is what a collapse flip is measured against: during a drag or a group move the strip shows
        // collapsed groups expanded for a moment, which is no state of the host's (see TabBarAdapter.setGroups).
        val previous = groups
        val before = if (expanded == null) previous
        else previous.filter { it.id != expanded } + newGroups.filter { it.id == expanded }.map { it.copy(collapsed = true) }
        groups = newGroups
        val map = HashMap<VirtualFile, String>()
        for (g in newGroups) for (f in g.members) map.putIfAbsent(f, g.id)
        index = map
        adapter.setGroups(newGroups, animate, before)
    }

    /** The group [file] belongs to in this split, per the groups last applied; safe to call from any thread. */
    fun groupIdOf(file: VirtualFile): String? = index[file]

    // ---- StripEvents -----------------------------------------------------------------------------------------------------

    private fun ref(header: GroupHeader) = HeaderRef(split, header.groupId, header, header.pillBounds())

    override fun headerClicked(header: GroupHeader, event: MouseEvent) {
        tabBar.host?.headerClicked(ref(header), event)
    }

    override fun headerRightClicked(header: GroupHeader, event: MouseEvent) {
        tabBar.host?.headerRightClicked(ref(header), event)
    }

    override fun headerMiddleClicked(header: GroupHeader, event: MouseEvent) {
        tabBar.host?.headerMiddleClicked(ref(header), event)
    }

    override fun headerHoverChanged(header: GroupHeader, hovering: Boolean) {
        tabBar.host?.headerHoverChanged(ref(header), hovering)
    }

    override fun hiddenTabSelected(groupId: String, file: VirtualFile) {
        tabBar.host?.hiddenTabSelected(split, groupId, file)
    }

    // IDE-2: like a drag result, the host takes the new order and the strip is refreshed. A sort never changes a collapsed flag, so
    // nothing flips; the refresh is an animating one only so that it leaves a running animation alone (the expand of a group dropped
    // in sorted mode), where a non-animating refresh would snap it to its end.
    override fun stripSorted(groups: List<GroupDecoration>) {
        tabBar.host?.groupsReordered(split, groups)
        tabBar.refresh(split, animate = true)
    }

    /** COLL-6: a tab of collapsed group [groupId] was picked in the overflow list. */
    fun collapsedTabChosen(groupId: String, file: VirtualFile) {
        tabBar.host?.collapsedTabChosen(split, groupId, file)
    }

    // ---- SplitGroups -----------------------------------------------------------------------------------------------------

    override fun groups(): List<GroupDecoration> = groups

    // Drag results land as they are, never animated (COLL-7): a drag may have expanded collapsed groups for a moment (live re-order,
    // a group arriving from another split), and the refresh that collapses them again is not a user toggle.
    // The one exception is the group that was dragged as its collapsed header and comes back expanded: it plays the expand animation.
    override fun commit(groups: List<GroupDecoration>, expanding: String?) {
        tabBar.host?.groupsChangedByDrag(split, groups)
        tabBar.refreshDropped(split, expanding)
    }

    override fun acceptGroup(group: GroupDecoration, expanding: String?) {
        tabBar.host?.groupMovedIn(split, group)
        tabBar.refreshDropped(split, expanding)
    }

    override fun dispose() {
    }
}
