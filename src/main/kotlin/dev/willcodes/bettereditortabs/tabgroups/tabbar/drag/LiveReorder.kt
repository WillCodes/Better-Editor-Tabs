package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader

/**
 * Live re-ordering of the strip during a drag, shared by the tab drag ([TabDragController]) and the group drag ([HeaderDragHelper]).
 * Wraps `TabBarAdapter.setGroups` / `applyOrder` of its split. EDT only.
 */
internal class LiveReorder(private val dnd: TabBarDnd) {
    /** [order] plus every open tab of the split it leaves out (should be none), in their current order. */
    fun completeOrder(order: List<VirtualFile>): List<VirtualFile> {
        val distinct = order.distinct()
        val rest = StripReader.allFiles(dnd.tabs).filter { it !in distinct }
        return distinct + rest
    }

    /**
     * Re-orders the strip to [flat]. Collapsed groups have no slot of their own in the platform (their tabs are hidden, the pill
     * is drawn behind an anchor tab), so they are expanded first and collapsed again afterwards, which recomputes the anchors.
     * [groups] are the groups as they were when the drag began. [emptyPills]: where the pills of empty groups (RULE-6) stand in the
     * new order, as the tab each stands behind ([dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots.derive]); they
     * have no file, so a file order alone cannot move them. Empty = leave them where they are.
     *
     * A group whose collapse is animating (COLL-7, a group left during a tab drag, COLL-8) still shows all its tabs, so it needs no
     * expanding first, and handing the groups to the strip again would end its animation: it is left alone.
     */
    fun reorder(groups: List<GroupDecoration>, flat: List<VirtualFile>, emptyPills: Map<String, VirtualFile?> = emptyMap()) {
        val collapsed = groups.any { it.collapsed && !dnd.adapter.collapseAnimator.isCollapsing(it.id) }
        if (collapsed) dnd.adapter.setGroups(groups.map { it.copy(collapsed = false) })
        dnd.adapter.applyOrder(flat)
        if (collapsed) dnd.adapter.setGroups(groups)
        if (emptyPills.isNotEmpty()) dnd.adapter.placeEmptyPills(emptyPills)
    }
}
