package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabPlacementRules
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import javax.swing.SwingUtilities

private val LOG = logger<TabPlacement>()

/**
 * MEM-2 for one split: a file opened for the first time in this split never lands inside a group.
 *
 * The platform inserts a new tab at `selectedIndex + 1` (or at the end with "open tabs at the end", `EditorWindow.addComposite`),
 * so with a group member selected it lands in the middle of the group. `TabsListener` has no "tab added" callback, but
 * `JBTabsImpl.addTabWithoutUpdating` adds the tab's `TabLabel` as a child component, so a `ContainerListener` on the tab bar sees it
 * synchronously. The correction runs right after the current EDT event (the tab is selected by then and stays selected: re-ordering
 * does not touch the selection). "New" means "file not in this split a moment ago", so re-orders, drag and drop and our own
 * hide/show cycles (which add and remove labels too) are ignored.
 *
 * Group membership comes from [groups] (the groups last applied to the split). Wraps `JBTabsImpl` (internal API, tab labels and the
 * tab list) and [TabBarAdapter.applyOrder]. [isDragActive] tells that a drag is running (then nothing is placed: re-orders by the
 * drag are not new tabs).
 */
internal class TabPlacement(
    private val adapter: TabBarAdapter,
    private val groups: () -> List<GroupDecoration>,
    private val isDragActive: () -> Boolean,
    parent: Disposable,
) {
    private val tabs = StripReader.tabsOf(adapter.window)
    private var known: Set<VirtualFile> = files()
    private var scheduled = false

    private val containerListener = object : ContainerListener {
        override fun componentAdded(e: ContainerEvent) {
            if (e.child is TabLabel) schedule()
        }

        override fun componentRemoved(e: ContainerEvent) {}
    }

    init {
        tabs.addContainerListener(containerListener)
        tabs.addListener(object : TabsListener {
            override fun tabRemoved(tabToRemove: TabInfo) {
                (tabToRemove.`object` as? VirtualFile)?.let { known = known - it }
            }
        }, parent)
        Disposer.register(parent) { tabs.removeContainerListener(containerListener) }
    }

    private fun files(): Set<VirtualFile> = tabs.tabs.mapNotNullTo(LinkedHashSet()) { it.`object` as? VirtualFile }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        SwingUtilities.invokeLater {
            scheduled = false
            place()
        }
    }

    private fun place() {
        val now = files()
        val added = now - known
        known = now
        if (added.isEmpty() || isDragActive()) return
        val groups = groups()
        if (groups.isEmpty()) return
        val spans = groups.map { TabPlacementRules.Span<VirtualFile>(it.id, it.members.toSet(), it.collapsed) }
        var order = tabs.tabs.mapNotNull { it.`object` as? VirtualFile }
        var changed = false
        for (file in added) {
            val info = tabs.findInfo(file) ?: continue
            val corrected = TabPlacementRules.orderForNewTab(order, file, spans) { info.isPinned } ?: continue
            order = corrected
            changed = true
        }
        if (!changed) return
        LOG.info("new tab(s) ${added.map { it.name }} opened inside a group: moved behind it")
        adapter.applyOrder(order)
    }
}
