package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabOrderRules
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration

/**
 * Collapse and expand (COLL-1..COLL-4): hides the tabs of collapsed groups and brings them back, and keeps the bookkeeping that
 * makes that reversible (which tabs are hidden, which tab each collapsed group sat behind, the selection to restore).
 *
 * Owns: `hiddenFiles`, the file-to-group map of hidden tabs, the anchors, the saved selection and the pending hidden selection.
 * Internal APIs wrapped, and why:
 * - `TabInfo.isHidden` (via `JBTabsImpl.updateHiding`): the only platform mechanism that removes a tab from the strip without closing
 *   its editor;
 * - `JBTabsImpl.sortTabs`: the strip order, including the hidden tabs, which keep a stale remembered index inside `JBTabsImpl`;
 * - `EditorWindow.setSelectedComposite`: moves the selection off a tab that is about to be hidden.
 *
 * EDT only.
 */
internal class CollapseController(private val tabs: JBEditorTabs, private val window: EditorWindow) {
    /** Files hidden by us, in left-to-right order at the time they were hidden. */
    private val hidden = LinkedHashSet<VirtualFile>()
    private val hiddenGroupOf = HashMap<VirtualFile, String>()

    /** For each collapsed group, the tab that stood in front of it when it was collapsed (null: it was the first). */
    private val anchors = HashMap<String, VirtualFile?>()

    /** The tab that was selected when the last visible tab was collapsed, to bring back if nothing happened in between. */
    private var savedSelection: VirtualFile? = null

    /** A hidden (collapsed) tab the platform has just selected (navigation); set only while the selection event is dispatched. */
    private var pendingHiddenSelection: VirtualFile? = null

    val hiddenFiles: Set<VirtualFile> get() = hidden

    fun isHidden(file: VirtualFile): Boolean = file in hidden

    fun hasHiddenFiles(): Boolean = hidden.isNotEmpty()

    /** True if collapsed group [groupId] has a remembered place. */
    fun hasAnchor(groupId: String): Boolean = groupId in anchors

    /** The tab in front of collapsed group [groupId], or null. */
    fun anchorOf(groupId: String): VirtualFile? = anchors[groupId]

    private fun visibleFiles() = StripReader.visibleFiles(tabs)
    private fun selectedFile() = StripReader.selectedFile(tabs)
    private fun infoOf(file: VirtualFile) = StripReader.infoOf(tabs, file)

    // ---- events from the strip ---------------------------------------------------------------------------------------------

    /**
     * The platform selected [file], a hidden tab (COLL-4): [expand] is told the group it belongs to and is expected to expand it
     * (the group's owner changes the groups and the strip calls [sync] again); `show` then selects [file] for real.
     */
    fun hiddenTabSelected(file: VirtualFile, expand: (groupId: String) -> Unit) {
        pendingHiddenSelection = file
        try {
            hiddenGroupOf[file]?.let(expand)
        }
        finally {
            pendingHiddenSelection = null
        }
    }

    /** Something visible was selected since the collapse, so the remembered tab is stale. */
    fun visibleTabSelected() {
        savedSelection = null
    }

    /**
     * The tab of [file] was closed. [before] is the tab that stood in front of it in the strip. A collapsed group anchored to the
     * closed tab stays where it was: behind the tab that stood before it.
     */
    fun tabRemoved(file: VirtualFile, before: VirtualFile?) {
        hidden.remove(file)
        hiddenGroupOf.remove(file)
        anchors.replaceAll { _, anchor -> if (anchor == file) before else anchor }
        if (savedSelection == file) savedSelection = null
    }

    // ---- collapse / expand ---------------------------------------------------------------------------------------------------

    /** Makes the hidden tabs match the collapsed groups of [groups]: shows what no longer is collapsed, hides what newly is. */
    fun sync(groups: List<GroupDecoration>) {
        val desired = LinkedHashSet<VirtualFile>()
        val groupOfFile = HashMap<VirtualFile, String>()
        for (g in groups) if (g.collapsed) for (f in g.members) if (infoOf(f) != null) {
            desired += f
            groupOfFile[f] = g.id
        }
        val toShow = hidden.filter { it !in desired }
        val toHide = desired.filter { it !in hidden }
        if (toShow.isNotEmpty()) show(toShow)
        if (toHide.isNotEmpty()) hide(toHide, desired, groupOfFile)
    }

    /**
     * Collapse (COLL-1/COLL-2). Order of operations matters:
     * 1. pick the tab to keep selected and select it *before* hiding: `JBTabsImpl.updateHiding` sets the selection to null silently
     *    (no event) when the selected tab is hidden, and the editor layer would not notice;
     * 2. remember where each group sat (anchor = tab before it) because the platform's own remembered index goes stale;
     * 3. hide right to left, one tab at a time: `hiddenInfos` is a HashMap, so hiding several at once and showing them again
     *    would restore them in hash order.
     */
    private fun hide(files: List<VirtualFile>, allDesired: Set<VirtualFile>, groupOfFile: Map<VirtualFile, String>) {
        val order = visibleFiles()
        for (id in files.mapNotNull { groupOfFile[it] }.distinct()) {
            if (id !in anchors) {
                val members = files.filter { groupOfFile[it] == id }.toSet()
                anchors[id] = TabOrderRules.anchorBefore(order, members)
            }
        }
        val selected = selectedFile()
        if (selected != null && selected in allDesired) {
            val target = TabOrderRules.nearestOutside(order, order.indexOf(selected)) { it in allDesired }
            if (target != null) {
                window.setSelectedComposite(target, false)
            }
            else {
                savedSelection = selected
            }
        }
        val ordered = order.filter { it in files }
        // Keep hidden tabs at the END of JBTabs' full tab list. The editor layer mixes "visible" and "all tabs" indices
        // (EditorWindow.computeIndexToSelect vs EditorTabbedContainer.getTabAt); they only agree if hidden tabs come last.
        applyVisibleOrder(order.filter { it !in files } + ordered)
        for (f in ordered) {
            hidden += f
            hiddenGroupOf[f] = groupOfFile.getValue(f)
        }
        for (f in ordered.asReversed()) {
            infoOf(f)?.isHidden = true
        }
        if (tabs.isEmptyVisible) {
            // Every tab is hidden. The platform keeps the previously selected editor component attached to the tab bar
            // (updateContainer only looks at visible tabs), so detach it to get an empty content area.
            for (f in ordered) infoOf(f)?.component?.let { c -> if (c.parent === tabs) tabs.remove(c) }
        }
    }

    /**
     * Expand (COLL-3): show the tabs again, put them back after their anchor and settle the selection.
     *
     * Selection rules (COLL-3 / COLL-4):
     * - a hidden tab the platform has just selected (navigation, Ctrl+E, ...) is shown *first*: `JBTabsImpl.updateHiding` ends in
     *   `updateAll`, which does `setSelectedInfo(getSelectedInfo())`, and `getSelectedInfo()` is null while the selected tab is
     *   still hidden. Showing any other tab first would silently reset the selection, and the editor would show the first tab;
     * - otherwise the selection does not change;
     * - `savedSelection` (the tab that was selected when the last visible tab was collapsed) is only used when the strip was
     *   still empty, i.e. nothing has been opened or selected since the collapse.
     */
    private fun show(files: List<VirtualFile>) {
        val emptyBefore = tabs.getVisibleInfos().isEmpty()
        val selectedBefore = selectedFile()
        val requested = pendingHiddenSelection?.takeIf { it in files }
        val shownByGroup = LinkedHashMap<String, MutableList<VirtualFile>>()
        if (requested != null) infoOf(requested)?.isHidden = false
        for (f in files) {
            infoOf(f)?.isHidden = false
            hidden.remove(f)
            hiddenGroupOf.remove(f)?.let { shownByGroup.getOrPut(it) { ArrayList() } += f }
        }
        var order = visibleFiles()
        for ((id, members) in shownByGroup) {
            val rest = order.filter { it !in members }
            val anchor = anchors.remove(id)
            order = TabOrderRules.placeGroup(rest, members, anchor?.takeIf { it in rest })
        }
        applyVisibleOrder(order)
        val target = when {
            requested != null -> requested
            selectedBefore != null -> selectedBefore
            emptyBefore -> savedSelection
            else -> null
        }
        savedSelection = null
        if (target != null && selectedFile() != target) {
            infoOf(target)?.takeIf { !it.isHidden }?.let { tabs.select(it, false) }
        }
    }

    private fun applyVisibleOrder(order: List<VirtualFile>) {
        val index = order.withIndex().associate { it.value to it.index }
        tabs.sortTabs(Comparator.comparingInt { info -> index[info.`object` as? VirtualFile] ?: Int.MAX_VALUE })
    }

    // ---- re-ordering around hidden tabs --------------------------------------------------------------------------------------

    /**
     * Re-pins collapsed tabs to the end of the full tab list if new tabs were opened since they were hidden
     * (the platform remembers an absolute index that goes stale). Cheap no-op when nothing is out of place. Returns true if it
     * re-ordered the strip.
     */
    fun repin(): Boolean {
        if (hidden.isEmpty()) return false
        val tail = tabs.tabs.takeLast(hidden.size)
        if (tail.all { it.isHidden }) return false
        applyOrder(visibleFiles() + hidden.toList())
        return true
    }

    /**
     * Re-orders all tabs of the split, collapsed ones included, to [order]. Hidden tabs keep their stale remembered index inside
     * `JBTabsImpl`, so this shows everything, sorts and hides again. Use this for any re-order around collapsed groups (drag and drop).
     */
    fun applyOrder(order: List<VirtualFile>) {
        val hiddenNow = hidden.toList()
        val ids = HashMap(hiddenGroupOf)
        val savedAnchors = HashMap(anchors)
        val keepSaved = savedSelection
        for (f in hiddenNow) infoOf(f)?.isHidden = false
        hidden.clear()
        hiddenGroupOf.clear()
        applyVisibleOrder(order)
        anchors.clear()
        anchors.putAll(savedAnchors)
        hide(hiddenNow, hiddenNow.toSet(), ids)
        if (keepSaved != null) savedSelection = keepSaved
    }

    /** Forgets every hidden tab (the split is being released); with [showTabs] the tabs are shown again first. */
    fun releaseAll(showTabs: Boolean) {
        if (showTabs) hidden.toList().forEach { infoOf(it)?.isHidden = false }
        hidden.clear()
        hiddenGroupOf.clear()
        anchors.clear()
    }
}
