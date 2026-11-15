package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.model.strip.SortedOrder
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.SwingUtilities

private val LOG = logger<StripSort>()

/**
 * IDE-2 with groups, for one strip: while "Sort tabs alphabetically" is on and the strip has at least one group, the plugin sorts the
 * strip (pinned tabs, then the groups by name with unnamed ones last, each group's tabs sorted, then the ungrouped tabs; the rule is
 * [SortedOrder]) and the platform's own sorting is off for it. A strip without groups is left to the platform exactly as before: its
 * alphabetical *view* (`JBEditorTabs.isAlphabeticalMode` follows the setting).
 *
 * Why not the platform's sort: it is only a view (`JBTabsImpl.getVisibleInfos` returns a sorted copy), it knows no groups, so members
 * would land anywhere and a group could never be contiguous (INV-2). So when the strip starts to be ours the view sorting is switched
 * off (`setAlphabeticalMode(false)`, which makes `JBEditorTabs` ignore the setting for good, `isAlphabeticalModeChanged`), the manual
 * order is remembered, and the strip is re-ordered physically ([applyOrder], the one correct way around collapsed groups). When it stops
 * being ours (sorting off, the last group gone, the strip disposed) the manual order is put back ([SortedOrder.restoreManual]) and
 * the view sorting goes back to the platform ([PlatformInternals.followUiSettings]).
 *
 * Re-sorting ([sync], only if the wanted order differs from what the strip shows) happens after [TabBarAdapter.setGroups] (membership
 * or name changes), when a tab label is added, when a tab's title changes (one coalesced `invokeLater`), after a pin change, when
 * `UISettings` change, and when a drag has ended; never while a drag runs ([dragRunning]: re-ordering live would fight it). The drop of
 * a drag changes membership and the refresh after it re-sorts, so a dragged tab or group lands where the sort puts it. After a re-sort
 * the groups with their members in the new order are reported ([report] = `StripEvents.stripSorted`); the refresh that follows
 * calls [afterGroups] again, which finds nothing to do (and [depth] stops anything else from looping).
 *
 * Internal APIs wrapped: `JBEditorTabs.setAlphabeticalMode`, `JBTabsImpl.sortTabs` / `tabs`, `TabInfo.text` / `isPinned` /
 * `changeSupport` (the title changes), the tab labels' container events. EDT only.
 */
internal class StripSort(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    /** The real groups, as last set. */
    private val groups: () -> List<GroupDecoration>,
    /** The strip's whole tab order, hidden tabs of collapsed groups at their headers' place. */
    private val logicalOrder: () -> List<VirtualFile>,
    /** Re-orders the whole strip (the adapter's `applyOrder`). */
    private val applyOrder: (List<VirtualFile>) -> Unit,
    /** Applies groups without any sort hook and without animation (the adapter's own `setGroups` body). */
    private val applyGroups: (List<GroupDecoration>) -> Unit,
    /** A drag of ours or the platform's is running in or over this strip. */
    private val dragRunning: () -> Boolean,
    /** `StripEvents.stripSorted`. */
    private val report: (List<GroupDecoration>) -> Unit,
    /** RULE-6: the groups shown as their pill alone, with the tab each pill stands behind (null: in front of every tab). */
    private val emptyAnchors: () -> Map<String, VirtualFile?> = { emptyMap() },
    /** RULE-6: puts empty groups' pills behind the given tabs (the adapter's `placeEmptyPills`). */
    private val placeEmpty: (Map<String, VirtualFile?>) -> Unit = {},
) {
    /** The plugin sorts this strip (see the class comment). */
    var isOwning = false
        private set

    /** The manual order, remembered when the sort became ours (what [SortedOrder.restoreManual] starts from). */
    private var manual: List<VirtualFile> = emptyList()

    /** A manual order stored before a restart, waiting for the strip's first [sync] (see [seedManual]). */
    private var seeded: List<VirtualFile>? = null

    /** Our own re-ordering is running: the label events it causes are not new tabs. */
    private var applying = false
    private var scheduled = false
    private var depth = 0
    private var disposed = false

    /** The platform's flag could not be reset: the strip's view sorting is set by hand and must follow the setting by hand. */
    private var viewSetByHand = false

    private val watched: MutableSet<TabInfo> = Collections.newSetFromMap(WeakHashMap())
    private val titleListener = PropertyChangeListener { e ->
        if (e.propertyName == TabInfo.TEXT && isOwning && !applying) recheckLater()
    }
    private val labelListener = object : ContainerListener {
        override fun componentAdded(e: ContainerEvent) {
            val label = e.child as? TabLabel ?: return
            watch(label.info)
            if (isOwning && !applying) recheckLater()
        }

        override fun componentRemoved(e: ContainerEvent) {}
    }

    init {
        tabs.addContainerListener(labelListener)
        for (info in tabs.tabs) watch(info)
    }

    private fun sortOn(): Boolean = UISettings.getInstance().sortTabsAlphabetically

    private fun watch(info: TabInfo) {
        if (watched.add(info)) info.changeSupport.addPropertyChangeListener(titleListener)
    }

    // ---- triggers -----------------------------------------------------------------------------------------------------------

    /**
     * [TabBarAdapter.setGroups] is about to apply [newGroups]. If the sort becomes ours with them, the manual order is read before
     * anything re-orders the strip (hiding the tabs of collapsed groups sorts the platform's list by the view order).
     */
    fun beforeGroups(newGroups: List<GroupDecoration>) {
        if (alive() && !isOwning && sortOn() && newGroups.isNotEmpty()) begin()
    }

    /** [TabBarAdapter.setGroups] has applied the groups. */
    fun afterGroups() = sync()

    /** `UISettings` changed (sorting switched on or off, or anything else: nothing to do then). */
    fun uiSettingsChanged() {
        if (!alive()) return
        if (!isOwning && viewSetByHand) {
            tabs.setAlphabeticalMode(sortOn())
            refreshView()
        }
        sync()
    }

    /** Something that can change the wanted order happened (a pin, the end of a drag): looks again after the current event. */
    fun recheckLater() {
        if (scheduled || !alive()) return
        scheduled = true
        SwingUtilities.invokeLater {
            scheduled = false
            sync()
        }
    }

    /** The manual order while the sort is ours (stored by the host, PERS-1, so that it survives a restart); null otherwise. */
    fun manualOrder(): List<VirtualFile>? = if (isOwning) manual else null

    /**
     * [order] is the strip's manual order as stored before a restart (the platform reopened the tabs in the sorted order, which is what
     * it saved). While the sort is ours it replaces the remembered order; otherwise it waits for the next [sync]: if the sort becomes
     * ours then, it is remembered instead of the current order; if sorting is off by then, the strip is put back into it right away.
     */
    fun seedManual(order: List<VirtualFile>) {
        if (isOwning) manual = order else seeded = order
    }

    // ---- the work -------------------------------------------------------------------------------------------------------------

    /**
     * Brings the strip in line with the settings: starts or stops owning the sort, and re-sorts if the strip is not in the wanted
     * order. A no-op while a drag runs (the end of the drag looks again) and when nothing is to be done.
     */
    fun sync() {
        if (!alive() || depth >= MAX_DEPTH || dragRunning()) return
        depth++
        try {
            val groups = groups()
            val wanted = sortOn() && groups.isNotEmpty()
            if (wanted && !isOwning) begin()
            else if (!wanted && !isOwning) restoreSeed(groups)
            else if (!wanted && isOwning) {
                stop(groups)
                return
            }
            if (isOwning) resort(groups)
        }
        finally {
            depth--
        }
    }

    private fun begin() {
        // the view first: `logicalOrder` reads `getVisibleInfos`, which is a sorted copy while the platform's view sorting is on
        tabs.setAlphabeticalMode(false)
        refreshView()
        manual = seeded ?: logicalOrder()
        seeded = null
        isOwning = true
        LOG.info("IDE-2: the plugin sorts this strip now (${manual.size} tabs)")
    }

    /** Sorting was switched off while the project was closed: the stored manual order is put back once, with the groups in it. */
    private fun restoreSeed(groups: List<GroupDecoration>) {
        val seed = seeded ?: return
        if (groups.isEmpty()) return // the groups have not arrived yet
        seeded = null
        val current = logicalOrder()
        val groupOf = groupOf(groups)
        val infos = infosByFile()
        val restored = SortedOrder.restoreManual(seed, current, { groupOf[it] }, { infos[it]?.isPinned == true })
        if (restored == current) return
        apply(restored, groups)
        reportIfChanged(groups)
    }

    private fun resort(groups: List<GroupDecoration>) {
        val current = logicalOrder()
        val items = items(current, groups)
        val wanted = SortedOrder.order(
            items,
            groups.map { SortedOrder.Group(it.id, it.name, it.creationIndex) },
            NaturalComparator.INSTANCE,
            String.CASE_INSENSITIVE_ORDER,
        )
        if (wanted.size != current.size) return // a tab without a file: leave the strip alone
        if (wanted != current) apply(wanted, groups)
        placeEmptyGroups(items, groups, wanted)
        reportIfChanged(groups)
    }

    /**
     * RULE-6: the pill of an empty group (a rule keeps its instance without tabs) stands among the groups by name, like the groups
     * with tabs: behind the last tab of the group that sorts before it.
     */
    private fun placeEmptyGroups(items: List<SortedOrder.Tab<VirtualFile>>, groups: List<GroupDecoration>, wanted: List<VirtualFile>) {
        val empty = emptyAnchors()
        if (empty.isEmpty()) return
        val spots = SortedOrder.emptySpots(
            items, groups.map { SortedOrder.Group(it.id, it.name, it.creationIndex) }, wanted, empty.keys, String.CASE_INSENSITIVE_ORDER,
        )
        if (spots.isNotEmpty() && spots.any { (id, anchor) -> !empty.containsKey(id) || empty[id] != anchor }) placeEmpty(spots)
    }

    private fun stop(groups: List<GroupDecoration>) {
        val current = logicalOrder()
        val groupOf = groupOf(groups)
        val infos = infosByFile()
        val restored = SortedOrder.restoreManual(manual, current, { groupOf[it] }, { infos[it]?.isPinned == true })
        if (restored != current) apply(restored, groups)
        isOwning = false
        manual = emptyList()
        val shown = if (groups.isEmpty()) groups else derive(groups, logicalOrder()) // before the platform's view sorting is back
        giveBack()
        LOG.info("IDE-2: the plugin no longer sorts this strip")
        if (shown != groups) report(shown)
    }

    /** The adapter is disposed: the manual order is put back and the view sorting goes back to the platform; nothing is reported. */
    fun release(windowAlive: Boolean) {
        if (disposed) return
        disposed = true
        tabs.removeContainerListener(labelListener)
        for (info in watched.toList()) info.changeSupport.removePropertyChangeListener(titleListener)
        watched.clear()
        if (!isOwning) return
        isOwning = false
        if (windowAlive) {
            val current = logicalOrder()
            val groupOf = groupOf(groups())
            val infos = infosByFile()
            val restored = SortedOrder.restoreManual(manual, current, { groupOf[it] }, { infos[it]?.isPinned == true })
            if (restored != current) applyOrder(restored)
        }
        giveBack()
    }

    /** Re-orders to [order] with collapsed groups expanded around it, which recomputes their anchors (the way a drag re-orders). */
    private fun apply(order: List<VirtualFile>, groups: List<GroupDecoration>) {
        applying = true
        try {
            val collapsed = groups.any { it.collapsed }
            if (collapsed) applyGroups(groups.map { it.copy(collapsed = false) })
            applyOrder(order)
            if (collapsed) applyGroups(groups)
        }
        finally {
            applying = false
        }
    }

    private fun reportIfChanged(groups: List<GroupDecoration>) {
        val next = derive(groups, logicalOrder())
        if (next != groups) report(next)
    }

    /** [groups] with their members, and the groups themselves, in the order of [order] (files that are not open keep their place behind the open ones). */
    private fun derive(groups: List<GroupDecoration>, order: List<VirtualFile>): List<GroupDecoration> {
        val index = HashMap<VirtualFile, Int>()
        for ((i, f) in order.withIndex()) index.putIfAbsent(f, i)
        val sorted = groups.map { g ->
            val open = g.members.filter { it in index }.sortedBy { index.getValue(it) }
            g.copy(members = open + g.members.filter { it !in index })
        }
        return sorted.sortedBy { g -> g.members.firstOrNull { it in index }?.let { index.getValue(it) } ?: Int.MAX_VALUE }
    }

    // ---- reading the strip ------------------------------------------------------------------------------------------------------

    private fun infosByFile(): Map<VirtualFile, TabInfo> {
        val map = HashMap<VirtualFile, TabInfo>()
        for (info in tabs.tabs) (info.`object` as? VirtualFile)?.let { map.putIfAbsent(it, info) }
        return map
    }

    private fun groupOf(groups: List<GroupDecoration>): Map<VirtualFile, String> {
        val map = HashMap<VirtualFile, String>()
        for (g in groups) for (f in g.members) map.putIfAbsent(f, g.id)
        return map
    }

    private fun items(order: List<VirtualFile>, groups: List<GroupDecoration>): List<SortedOrder.Tab<VirtualFile>> {
        val infos = infosByFile()
        val groupOf = groupOf(groups)
        return order.mapNotNull { f -> infos[f]?.let { SortedOrder.Tab(f, it.text, it.isPinned, groupOf[f]) } }
    }

    // ---- the platform's view sorting ----------------------------------------------------------------------------------------------

    /** The strip follows the setting again (the platform's alphabetical view), or, if its flag is out of reach, is set by hand. */
    private fun giveBack() {
        viewSetByHand = !PlatformInternals.followUiSettings(tabs)
        if (viewSetByHand) tabs.setAlphabeticalMode(sortOn())
        refreshView()
    }

    /** The platform's tab list cache is dropped and the strip laid out again: a stable no-op sort does exactly that (`JBTabsImpl.sortTabs`). */
    private fun refreshView() {
        tabs.sortTabs(Comparator<TabInfo> { _, _ -> 0 })
    }

    private companion object {
        /** A re-sort that triggers another one that triggers another one is a bug; stop at this depth. */
        const val MAX_DEPTH = 3
    }
}
