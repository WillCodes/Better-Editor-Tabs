package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting
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
 * Animation (COLL-7): a collapse is animated by [CollapseAnimator] while the tabs are still visible; the selection moves off the group
 * at the start ([selectOutside]) and the tabs are hidden here ([sync] / [hide]) only when the animation is over. An expand shows the
 * tabs at once ([show]); the animator then grows them from nothing. Nothing in this class knows about the animation.
 *
 * EDT only.
 */
internal class CollapseController(private val tabs: JBEditorTabs, private val window: EditorWindow) {
    /** Files hidden by us, in left-to-right order at the time they were hidden. */
    private val hidden = LinkedHashSet<VirtualFile>()
    private val hiddenGroupOf = HashMap<VirtualFile, String>()

    /** For each collapsed group, the tab that stood in front of it when it was collapsed (null: it was the first). */
    private val anchors = HashMap<String, VirtualFile?>()

    /**
     * When each collapsed group collapsed: a counter, increasing left to right within one pass. Orders pills that end up at the same
     * place (see [collapseOrder]).
     */
    private val collapsedAt = HashMap<String, Long>()
    private var collapseCounter = 0L

    /** The tab that was selected when the last visible tab was collapsed, to bring back if nothing happened in between. */
    private var savedSelection: VirtualFile? = null

    /** A hidden (collapsed) tab the platform has just selected (navigation); set only while the selection event is dispatched. */
    private var pendingHiddenSelection: VirtualFile? = null

    val hiddenFiles: Set<VirtualFile> get() = hidden

    fun isHidden(file: VirtualFile): Boolean = file in hidden

    fun hasHiddenFiles(): Boolean = hidden.isNotEmpty()

    /** True if collapsed group [groupId] has a remembered place. */
    fun hasAnchor(groupId: String): Boolean = groupId in anchors

    /**
     * The visible tab in front of collapsed group [groupId], or null (the group is first). The remembered anchor can itself be hidden:
     * a collapsed group that stood right behind another group's tab keeps that tab as its anchor when the other group collapses
     * later. Then the place is the other group's place, so this follows that group's anchor, and so on, to a visible tab. The
     * remembered anchors are not rewritten, so when the other group expands again this group is back behind its tab.
     */
    fun anchorOf(groupId: String): VirtualFile? {
        var anchor = anchors[groupId]
        val seen = hashSetOf(groupId)
        while (anchor != null && anchor in hidden) {
            val owner = hiddenGroupOf[anchor] ?: break
            if (!seen.add(owner)) return null
            anchor = anchors[owner]
        }
        return anchor
    }

    /**
     * The collapsed group whose tab collapsed group [groupId] stood right behind (its remembered anchor is now hidden by that group),
     * with that tab; null if the anchor is visible or there is none. Such a group's pill belongs directly behind the other group's.
     */
    fun anchorParent(groupId: String): Pair<String, VirtualFile>? {
        val anchor = anchors[groupId] ?: return null
        if (anchor !in hidden) return null
        val owner = hiddenGroupOf[anchor] ?: return null
        return owner to anchor
    }

    /**
     * When collapsed group [groupId] collapsed relative to the others (smaller = earlier; [Long.MAX_VALUE] if unknown). Two collapsed
     * groups with the same visible anchor stood in this order: a group that collapses next to an already collapsed one (same tab in
     * front of both) stood behind its pill; within one pass, groups collapse left to right.
     */
    fun collapseOrder(groupId: String): Long = collapsedAt[groupId] ?: Long.MAX_VALUE

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
        // left to right, so that collapseOrder ranks the groups of one pass by their place
        val ids = files.mapNotNull { groupOfFile[it] }.distinct()
            .sortedBy { id -> order.indexOfFirst { groupOfFile[it] == id }.let { if (it < 0) Int.MAX_VALUE else it } }
        val alreadyCollapsed = anchors.keys.toSet()
        for (id in ids) {
            if (id !in anchors) {
                val members = files.filter { groupOfFile[it] == id }.toSet()
                val visibleBefore = TabOrderRules.anchorBefore(order, members)
                // Collapsed groups whose pills already stand between that tab and this group: this group stood behind the last of
                // them, so it remembers that group's last tab (hidden). anchorOf / anchorParent then keep it behind that group, also
                // when that group expands again.
                anchors[id] = lastTabOfGroupAt(visibleBefore, alreadyCollapsed) ?: visibleBefore
                collapsedAt[id] = ++collapseCounter
            }
        }
        val selected = selectedFile()
        // Usually done already when the collapse animation started ([selectOutside]); then the selected tab is outside the groups.
        if (selected != null && selected in allDesired && !selectOutside(allDesired)) savedSelection = selected
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
     * COLL-2: if the selected tab is one of [collapsing], selects the nearest visible tab outside of it (right / below first, then
     * left / above) and returns true; false if there is no such tab (the selection is left alone). True without doing anything if
     * the selected tab is not in [collapsing]. Idempotent. Called when a collapse animation *starts*, so the editor never waits for
     * the animation, and again by [hide].
     */
    fun selectOutside(collapsing: Set<VirtualFile>): Boolean {
        val selected = selectedFile()
        if (selected == null || selected !in collapsing) return true
        val order = visibleFiles()
        val target = TabOrderRules.nearestOutside(order, order.indexOf(selected)) { it in collapsing } ?: return false
        window.setSelectedComposite(target, false)
        return true
    }

    /**
     * The last hidden tab of the last collapsed group (among [candidates]) whose pill stands right behind the visible tab [anchor]
     * (null = at the front), in pill order ([PillHosting.orderShared], as the strip draws them); null if there is none.
     */
    private fun lastTabOfGroupAt(anchor: VirtualFile?, candidates: Set<String>): VirtualFile? {
        val here = candidates.filter { anchorOf(it) == anchor }
        if (here.isEmpty()) return null
        val membersOf = hidden.groupBy { hiddenGroupOf[it] }
        val ordered = PillHosting.orderShared(
            here,
            parentOf = { id -> anchorParent(id)?.first?.takeIf { it in here } },
            siblingRank = { id ->
                val parent = anchorParent(id)
                if (parent != null && parent.first in here) membersOf[parent.first].orEmpty().indexOf(parent.second).toLong()
                else collapseOrder(id)
            },
            isOwn = { false },
        )
        return membersOf[ordered.last()]?.lastOrNull()
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
        // Where each group comes back, resolved while the hidden bookkeeping is still complete: its own anchor if that tab is shown in
        // this pass too, otherwise the nearest visible tab in front of it (see anchorOf).
        val showing = files.toSet()
        val places = files.mapNotNull { hiddenGroupOf[it] }.distinct().associateWith { id ->
            anchors[id]?.takeIf { it in showing } ?: anchorOf(id)
        }
        if (requested != null) infoOf(requested)?.isHidden = false
        for (f in files) {
            infoOf(f)?.isHidden = false
            hidden.remove(f)
            hiddenGroupOf.remove(f)?.let { shownByGroup.getOrPut(it) { ArrayList() } += f }
        }
        val placing = shownByGroup.values.flatten().toSet()
        var order = visibleFiles().filter { it !in placing }
        // A group anchored on a tab of another group that comes back in the same pass is placed after that group.
        val pending = LinkedHashMap(shownByGroup)
        while (pending.isNotEmpty()) {
            val (id, members) = pending.entries.firstOrNull { (id, _) -> places[id].let { it == null || it !in placing || it in order } }
                ?: pending.entries.first()
            pending.remove(id)
            anchors.remove(id)
            collapsedAt.remove(id)
            order = TabOrderRules.placeGroup(order, members, places[id]?.takeIf { it in order })
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
        collapsedAt.clear()
    }
}
