package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabOrderRules

/**
 * Ordering operations of the strip around collapsed groups: re-ordering the visible tabs, re-pinning hidden ones, applying a full
 * order, and keeping a group where it is when a tab is pinned or unpinned (including the hand-off to the alphabetical sort and the pin order fix).
 */
internal class StripOrder(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val collapse: CollapseController,
    private val tint: TabTint,
    private val applier: GroupsApplier,
    private val drag: () -> StripDragSurface,
    private val dragActive: () -> Boolean,
    /** True while the plugin sorts the strip (the sort puts pinned tabs first itself). */
    private val sortOwns: () -> Boolean,
    private val visibleFiles: () -> List<VirtualFile>,
    private val updateReservations: () -> Boolean,
) {
    /** The logical order as of the last layout pass: the order before a pin change, which the platform applies lazily (see [pinChanged]). */
    var lastLaidOutOrder: List<VirtualFile> = emptyList()

    /**
     * Re-orders the *visible* tabs to [visibleOrder] and leaves the tabs hidden by collapsed groups where they are (behind the visible
     * ones, each collapsed group keeping its anchor). Files of [visibleOrder] that are not visible are ignored; visible tabs it does
     * not list keep their relative order behind the listed ones. Goes through [applyOrder] (the only correct way around hidden tabs).
     *
     * [CollapseController.applyOrder] shows every hidden tab, sorts by the given order (a file missing from it sorts last) and hides
     * them again, re-deriving their order from the strip. Passing only visible files would leave the hidden tabs' relative order to
     * the order in which the platform re-inserts them, so the full order is built here: visible tabs as asked, hidden ones behind
     * them in their current order (the invariant the collapse code keeps: hidden tabs at the end of the full tab list).
     */
    fun reorderVisible(visibleOrder: List<VirtualFile>) {
        if (!alive()) return
        val visible = visibleFiles()
        val wanted = visibleOrder.toSet()
        val listed = visibleOrder.filter { it in visible }.distinct()
        // Our own left-to-right record, not `JBTabsImpl.getTabs()`: that re-inserts hidden tabs by iterating a HashMap, so their order
        // there is arbitrary, and feeding it back would swap the tabs of a collapsed group when it is expanded.
        val hiddenNow = collapse.hiddenFiles.toList()
        applyOrder(listed + visible.filter { it !in wanted } + hiddenNow)
    }

    /**
     * Re-pins collapsed tabs to the end of the full tab list if new tabs were opened since they were hidden (see
     * [CollapseController.repin]) and refreshes tint and pill placement.
     */
    fun repinHidden() = applier.own {
        tint.refresh()
        updateReservations()
        if (collapse.repin()) {
            updateReservations()
            tabs.revalidate()
        }
    }

    /**
     * Re-orders all tabs of the split, collapsed ones included, to [order]. Hidden tabs keep their stale remembered index inside
     * `JBTabsImpl`, so this shows everything, sorts and hides again. Use this for any re-order around collapsed groups (drag and drop).
     */
    fun applyOrder(order: List<VirtualFile>) = applier.own {
        collapse.applyOrder(order)
        updateReservations()
        tabs.revalidate()
    }

    /**
     * A tab of this strip was pinned or unpinned (`FileEditorManagerListener.filePinStateChanged`, which the platform publishes after
     * setting the flag and before its next layout). With "keep pinned tabs on the left" the platform groups the pinned tabs first
     * lazily, in `JBTabsImpl.getVisibleInfos`, by *swapping* a pinned tab with the first unpinned one, so that tab jumps to where the
     * pinned one was, e.g. behind a group, and the group looks moved. Here the strip is re-ordered first: pinned tabs first, everything
     * else in the order of the last layout pass ([TabOrderRules.pinnedFirst]); the platform's grouping then finds nothing to swap.
     * Not while the plugin sorts the strip (the sort puts pinned tabs first itself) or a drag runs.
     */
    fun pinChanged() {
        if (!alive() || sortOwns() || dragActive() || drag().isForeignBusy()) return
        // the platform's alphabetical view (a strip without groups) orders pinned tabs itself
        if (UISettings.getInstance().sortTabsAlphabetically || !keepPinnedTabsFirst()) return
        // read the raw list without `getVisibleInfos`, which would apply the platform's swap right now
        val pinned = tabs.tabs.filter { it.isPinned }.mapNotNullTo(HashSet()) { it.`object` as? VirtualFile }
        val all = StripReader.allFiles(tabs)
        val wanted = TabOrderRules.pinnedFirst(lastLaidOutOrder, all) { it in pinned }
        if (wanted != lastLaidOutOrder) applyOrder(wanted)
    }

    /** The platform setting "keep pinned tabs on the left" (`AdvancedSettings`, public API); on if it cannot be read. */
    private fun keepPinnedTabsFirst(): Boolean =
        try { AdvancedSettings.getBoolean("editor.keep.pinned.tabs.on.left") } catch (e: Exception) { true }
}
