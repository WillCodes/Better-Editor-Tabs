package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLayout
import java.awt.Rectangle

/**
 * The row for pills that a separate pinned row leaves without a host (HDR-6, SCOPE-5).
 *
 * With "Show pinned tabs in a separate row" the platform lays the pinned tabs out in a row of their own and the other tabs in the rows
 * after it (`MultiRowLayout.splitToPinnedUnpinned`). Groups are never pinned (INV-3), so a pill belongs in the unpinned rows. When every
 * unpinned tab is hidden by collapsed groups, the platform has no unpinned row at all, and a pill hosted by the last pinned tab would
 * show up in the pinned row. Instead [PillLayout] leaves such pills without a host (loose), and this class makes room for them: after
 * every platform layout pass ([TabBarAdapter]'s layout hook) it moves the selected editor component down by one row (Top; for Bottom it
 * shortens it), which frees a strip row right next to the pinned row, where the unpinned row would be. [StripGeometry] places the loose
 * pills there and [PillAwareTabsBorder] draws the strip line along it. The platform lays the component out afresh in its next pass, so
 * nothing has to be undone when the row is no longer needed. A pass is adjusted once ([lastPass] identity), so the hook may run twice.
 *
 * Limits: the platform does not know about the row (its drag-out and hit-testing areas end at the pinned row). Side placements have no
 * separate pinned row.
 *
 * The same row is the place of the gap that a tab or group dragged in from another split opens at the end of a strip that holds nothing
 * but pinned tabs (the arrival is unpinned, so it belongs after the pinned row, `PillLayout.resolveSpacer`).
 *
 * Wraps: `TabLayout.showPinnedTabsSeparately()`, `JBTabsImpl.lastLayoutPass`, `headerFitSize`, `tabsPosition`, `selectedInfo`. EDT only.
 */
internal class SpareRow(private val tabs: JBEditorTabs, private val layout: PillLayout) {
    private var lastPass: Any? = null

    /** The row (tab-bar coordinates) while the pills need it, else null. Valid after [apply]. */
    var rect: Rectangle? = null
        private set

    /** Makes room for the row in the pass that was just laid out, or forgets it. Returns true if the row is in use. */
    fun apply(): Boolean {
        val infos = tabs.getVisibleInfos()
        val position = tabs.tabsPosition
        val active = !tabs.isHideTabs && infos.isNotEmpty() && pinnedRowApart(tabs) && (layout.hasLoosePills() || layout.hasLooseSpacer()) &&
                     (position == JBTabsPosition.top || position == JBTabsPosition.bottom)
        val pass = tabs.lastLayoutPass
        val row = tabs.headerFitSize?.height ?: 0
        val first = tabs.getTabLabel(infos.firstOrNull() ?: return clear())?.bounds
        if (!active || pass == null || row <= 0 || first == null || first.isEmpty) return clear()
        // The pinned row is the only row (every unpinned tab is hidden); the spare row sits on its editor side.
        val top = position == JBTabsPosition.top
        rect = Rectangle(0, if (top) first.y + row else first.y - row, tabs.width, row)
        if (pass !== lastPass) {
            lastPass = pass
            val component = tabs.selectedInfo?.component
            if (component != null && component.parent === tabs && component.height > 0) {
                val b = component.bounds
                val height = maxOf(0, b.height - row)
                component.setBounds(b.x, if (top) b.y + row else b.y, b.width, height)
                component.validate()
            }
        }
        return true
    }

    private fun clear(): Boolean {
        rect = null
        lastPass = null
        return false
    }

    companion object {
        /** True if pinned tabs are laid out in a row of their own in [tabs] (Top / Bottom only, as the platform's editor layouts do). */
        fun pinnedRowApart(tabs: JBEditorTabs): Boolean = tabs.isHorizontalTabs && TabLayout.showPinnedTabsSeparately()
    }
}
