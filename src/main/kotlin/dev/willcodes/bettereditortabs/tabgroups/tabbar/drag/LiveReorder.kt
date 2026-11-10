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
     * [groups] are the groups as they were when the drag began.
     */
    fun reorder(groups: List<GroupDecoration>, flat: List<VirtualFile>) {
        val collapsed = groups.any { it.collapsed }
        if (collapsed) dnd.adapter.setGroups(groups.map { it.copy(collapsed = false) })
        dnd.adapter.applyOrder(flat)
        if (collapsed) dnd.adapter.setGroups(groups)
    }
}
