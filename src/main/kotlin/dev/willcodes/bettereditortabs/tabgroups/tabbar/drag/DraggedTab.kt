package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo

/**
 * The tab a drag carries, as far as a [TabDragSession] needs to know it: its file, its `TabInfo` in the strip it was picked up from
 * (text, icon and pinned state, which the floating picture and the drop rules use) and that [source] strip.
 *
 * It is deliberately not "a tab of the strip the session runs on": a tab dragged from one split over another split's strip is a
 * tab of [source] while the session runs on the other strip (DRAG-5, [CrossSplitTabDrag]).
 */
internal class DraggedTab(val file: VirtualFile, val info: TabInfo, val source: TabBarDnd) {
    /**
     * The tab's label size as it was when the drag began: the preferred width and the height of its row. The label is not laid out while the
     * tab is out of its strip (the drag is over another split, `StripDragSurface.beginTransit`), and the gap other strips open for the tab is
     * sized from this. The width is the tab's own: without the space its label reserves for pills (a collapsed or empty group's pill hosted
     * beside it, a partial expand's header and count, HDR-6), which stay in the source strip (user report, Stage 11.5 fix round 6: the gap
     * in another split was as wide as the tab and the pills next to it).
     */
    val labelSize: java.awt.Dimension? = source.tabs.getTabLabel(info)?.let { label ->
        val (lead, trail) = source.adapter.reservedSpace(file) ?: (0 to 0)
        java.awt.Dimension((label.preferredSize.width - lead - trail).coerceAtLeast(1), label.height)
    }

    /** Live: the drop rules keep pinned tabs left of unpinned ones when the platform setting asks for it. */
    val pinned: Boolean get() = info.isPinned
}
