package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabOrderRules
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals

/**
 * A tab that is **in transit**: it has left its strip because the drag that carries it is over another split's strip, as the
 * platform's own tab drag does it when the tab is dragged out (`EditorTabbedContainerDragOutDelegate.dragOutStarted`, `EditorTabbedContainer.kt:460`):
 * the editor area first selects the tab it would select if this one was closed ([PlatformInternals.tabToSelectOnDragOut], the platform's rule),
 * then the tab is hidden (`TabInfo.isHidden`); `dragOutCancelled` shows it again. Dropping it back on its own strip, Esc and a copy show it
 * again here ([end]); a move closes it.
 *
 * `TabInfo.isHidden` is also how [CollapseController] hides the tabs of collapsed groups, so the two must not meet: a tab in transit is
 * hidden by this class only and is **not** in the collapse bookkeeping (`hiddenFiles`, anchors, the end-of-the-list rule). It stays
 * where it was in the platform's list (the platform remembers the index it had, `JBTabsImpl.updateHiding`), the collapse code only
 * ever sorts and counts the visible tabs and its own hidden ones, so none of it sees the tab; the close and hidden-tab-selection handling is not involved (the tab
 * is not closed, and every selection made here runs as the adapter's own change, which reports no hidden-tab selection). The pill of a group whose only
 * tab is in transit stays parked where it was ([tabsBefore]), like the pill of a group whose only tab is dragged inside its strip.
 *
 * Internal APIs wrapped: `TabInfo.isHidden`, `EditorWindow.setSelectedComposite`, `EditorWindow.getTabToSelect` (via [PlatformInternals]),
 * `JBTabsImpl.getIndexOf`. EDT only.
 */
internal class TabTransit(
    private val tabs: JBEditorTabs,
    private val window: EditorWindow,
    private val collapse: CollapseController,
) {
    /**
     * [selectedBefore]: the file that was selected when the tab left (put back by [end] with `reselect`). [tabsBefore]: for every expanded
     * group, the tabs that stood in front of it then, which is where its pill stays if the tab was its only one.
     */
    class State(val info: TabInfo, val file: VirtualFile, val selectedBefore: VirtualFile?, val tabsBefore: Map<String, Set<VirtualFile>>)

    /** The tab in transit, or null. */
    var state: State? = null
        private set

    /**
     * The tab of [file] leaves the strip. [groups] are the groups the strip shows (for the parked pill). False (nothing changed) if the tab is
     * not showing, is in transit already or another tab is. The platform's rule picks the selection; where it cannot be asked, the
     * neighbour that follows the tab (else the one before it) is selected, as `EditorWindow.computeIndexToSelect` does by default.
     */
    fun begin(file: VirtualFile, groups: List<GroupDecoration>): Boolean {
        if (state != null) return false
        val info = StripReader.infoOf(tabs, file) ?: return false
        if (info.isHidden || collapse.isHidden(file)) return false
        val visible = StripReader.visibleFiles(tabs)
        val before = HashMap<String, Set<VirtualFile>>()
        for (g in groups) {
            if (g.collapsed) continue
            val first = visible.indexOfFirst { it in g.members }
            if (first >= 0) before[g.id] = visible.subList(0, first).toHashSet()
        }
        val selected = StripReader.selectedFile(tabs)
        val next = tabToSelect(info, file, visible)
        // "setting isHidden to true will hide the tab - we must select another tab now" (dragOutStarted)
        if (next != null && next != file) window.setSelectedComposite(next, false)
        state = State(info, file, selected, before)
        // The platform hides the tab and nothing else: when it was the strip's last showing tab the editor keeps showing its file until
        // the drop (a move closes the tab, a copy or Esc shows it again), so the user sees only the tab leave.
        info.isHidden = true
        return true
    }

    /**
     * The tab comes back ([show]: it is shown in its place again unless it is hidden by a collapse; false for a tab that is about to be
     * closed) and, with [reselect], the tab that was selected when it left is selected again. Returns the state that ended, null if no
     * tab was in transit.
     */
    fun end(show: Boolean, reselect: Boolean): State? {
        val s = state ?: return null
        state = null
        if (show && !collapse.isHidden(s.file)) s.info.isHidden = false
        if (reselect) selectAgain(s)
        return s
    }

    /** The tab of [file] was closed: nothing is in transit any more. */
    fun forget(file: VirtualFile) {
        if (state?.file == file) state = null
    }

    private fun selectAgain(s: State) {
        val file = s.selectedBefore ?: return
        val info = StripReader.infoOf(tabs, file) ?: return
        if (!info.isHidden && StripReader.selectedFile(tabs) != file) window.setSelectedComposite(file, false)
    }

    /** The file the editor area selects when the tab of [file] goes (see [begin]); null if there is none. */
    private fun tabToSelect(info: TabInfo, file: VirtualFile, visible: List<VirtualFile>): VirtualFile? {
        val answer = PlatformInternals.tabToSelectOnDragOut(window, info, file, tabs.getIndexOf(info))
        if (answer is PlatformInternals.TabToSelect.Tab) {
            val chosen = answer.info
            // the platform's list mixes visible and hidden tabs; a hidden one cannot be selected
            val chosenFile = chosen?.`object` as? VirtualFile
            if (chosenFile != null && chosenFile != file && !chosen.isHidden && chosenFile in visible) return chosenFile
            if (chosen == null || chosenFile == file) return null
        }
        return TabOrderRules.nearestOutside(visible, visible.indexOf(file)) { it == file }
    }
}
