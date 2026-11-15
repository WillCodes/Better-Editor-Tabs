package dev.willcodes.bettereditortabs.tabgroups.tabbar.splits

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.StripFreeze
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabClose
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import java.awt.Rectangle
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

private val LOG = logger<TemporarySplits>()

/**
 * Temporary splits: the machinery that keeps a split alive while it holds only empty groups.
 *
 * An editor split exists only while it has a tab: closing its last tab closes the split (`EditorWindow.closeFile`: `tabCount == 0` ->
 * `removeFromSplitter`, or, in a detached window, `DockWindow.closeIfEmpty`), and `EditorWindow.split` / `openFileInNewWindow` need a
 * file. An empty group has no tab, so the split it stands in is held open by one **placeholder tab** ([PlaceholderFile]): opened in the
 * split and hidden at once (`TabInfo.isHidden`, as collapse hides tabs; `EditorWindow.tabCount` counts hidden tabs, so the split
 * stays; the strip shows only the pills and the empty-editor overlay covers the content). The placeholder is never a file of
 * the user's: `StripReader.allFiles`, `SplitIdentity`, `TabPlacement` and the close scoping leave it out, the tab limit never closes it,
 * and no history records it ([PlaceholderFile]).
 *
 * - **Created** by a drop of an empty group's header on an editor edge or outside every editor (`moveGroupToNewSplit` /
 *   `moveGroupToNewWindow` open the new split or window with it), and by **keep-alive** ([beforeFileClosed]): the split's last real
 *   tab is about to close (`FileEditorManagerListener.Before.beforeFileClosed`, published inside `EditorWindow.closeFile` before the tab
 *   goes, whatever the cause: close, group or tab move, stash, delete) while it still holds an empty group, and the split would
 *   actually close ([wouldClose]; not the main area's last window, which stays open empty anyway): the placeholder is opened first.
 * - **Becomes normal**: a real tab arrives; the placeholder is closed quietly (no Reopen Closed Tab entry). **Ends**: the host lists no
 *   group for the split any more; the placeholder is closed and the platform closes the split. Both are looked at after the current
 *   event ([schedule]), never inside the platform's own tab changes.
 * - **Closed by the user** (the placeholder is closed by anything but us, or the split goes away without it, e.g. an unsplit merges it
 *   into a sibling or a detached window is closed): the host is told through `TabBarHost.temporarySplitClosing` once, before the split
 *   is disposed when the platform lets us ([beforeFileClosed]) and else when the disposal is noticed ([windowGone]); it moves the empty
 *   groups to the split used last that has tabs or deletes them. Nothing happens for a window that survives its placeholder (the main
 *   area's last window), or while the project closes.
 *
 * Platform facts relied on (262 sources, verified): `EditorWindow.closeFile` ends in `removeFromSplitter` only when `tabCount == 0`
 * (hidden tabs count: `JBTabsImpl.getTabCount` is `tabs.size`) and the window is in a splitter; `EditorWindow.unsplit` moves the
 * sibling's composites, a placeholder included, into the surviving window; `DockWindow.closeIfEmpty` runs after every file closed in a
 * detached window and closes it when `EditorsSplitters.isEmptyVisible` (no visible tab) holds, so a placeholder opened by keep-alive
 * stays visible until that close is over and is hidden in the next event ([unsettled]); a picture of the split ([freezeSplit]) covers
 * it from just before it opens until it is hidden, so it is never seen; `EditorWindow.split` returns null for a window without tab.
 *
 * The Switcher (Ctrl+Tab) and Recent Files (Ctrl+E): the default implementation (the `platform-recentFiles-plugin`, 262 and 263) passes
 * every file it lists through `RecentFilesExcluderKt.isAllowedInRecentFilesModel`, which drops an `IdeDocumentHistoryImpl.OptionallyIncluded`
 * file whose `isIncludedInDocumentHistory` is false, as the placeholder's is ([PlaceholderFile]). Only the old implementation (registry
 * `switcher.use.fallback.in.monolith`, off by default) lists `selectionHistory` / `openFiles` unfiltered; picking the placeholder there
 * selects the hidden tab in its own split, which only makes that temporary split current (the empty-editor overlay stays).
 *
 * Known limit: a detached window with only hidden tabs counts as empty for `DockManagerImpl.createDragSession`, which makes it half
 * transparent during the platform's own tab drags. EDT only.
 */
internal class TemporarySplits(private val project: Project, private val tabBar: TabBar) {
    /** Windows seen holding a placeholder (so that a disposal that nobody announced can be told from any other). Weak: identity of the window. */
    private val tracked: MutableSet<EditorWindow> = Collections.newSetFromMap(WeakHashMap())

    /** Windows whose closing was handed to the host already. */
    private val handled: MutableSet<EditorWindow> = Collections.newSetFromMap(WeakHashMap())

    /** Windows whose placeholder was opened by keep-alive in the middle of a close and is still visible (see the class comment). */
    private val unsettled = HashSet<EditorWindow>()

    /** Placeholder -> the window it was seen in, to clean up Reopen Closed Tab history after a close that was not ours. */
    private val owners = IdentityHashMap<VirtualFile, EditorWindow>()

    private val queued = LinkedHashSet<EditorWindow>()
    private var queueScheduled = false

    /** Depth of closes that are ours (becoming normal, ending, project close): not "the user closed the temporary split". */
    private var quiet = 0
    private var settling = false

    // ---- queries ---------------------------------------------------------------------------------------------------------

    /** The placeholder's tab in [window], or null. */
    private fun placeholderIn(window: EditorWindow): TabInfo? =
        StripReader.tabsOf(window).tabs.firstOrNull { isPlaceholder(it.`object` as? VirtualFile) }

    /** True if [window] holds a placeholder: it is a temporary split. */
    fun isTemporary(window: EditorWindow): Boolean = !window.isDisposed && placeholderIn(window) != null

    /**
     * True if [window] goes away when its last tab closes: it stands in a splitter (the platform removes it) or is a detached window
     * (`DockWindow.closeIfEmpty`). The main area's last window does not: it stays open empty, so it needs no placeholder.
     */
    fun wouldClose(window: EditorWindow): Boolean = window.inSplitter() || window.owner.isFloating

    // ---- opening ------------------------------------------------------------------------------------------------------------

    /**
     * The placeholder of [window]: the one it has, or a new one opened in the background (neither selected nor focused). Not hidden
     * yet: [settle] does that at the next sync (the new tab's `fileOpened`), or the caller does with [hideNow]. Null if the file did
     * not open (a platform change).
     */
    fun ensure(window: EditorWindow): PlaceholderFile? {
        (placeholderIn(window)?.`object` as? PlaceholderFile)?.let { return it }
        val file = PlaceholderFile()
        FileEditorManagerEx.getInstanceEx(project).openFile(file, window, PlatformInternals.backgroundOpenOptions())
        if (window.getComposite(file) == null) {
            LOG.warn("the placeholder tab did not open in the split")
            return null
        }
        return file
    }

    /**
     * The split [window] that a group move made around a new placeholder ([PlaceholderFile.fresh]) is complete (the host has heard of
     * the group, or the move failed): from now on it can end like any temporary split.
     */
    fun created(window: EditorWindow) {
        (placeholderIn(window)?.`object` as? PlaceholderFile)?.fresh = false
        schedule(window)
    }

    /** Hides the placeholder of [window] if it is not hidden. Own change of the strip. */
    private fun hide(window: EditorWindow, info: TabInfo) {
        if (info.isHidden) return
        val tabs = StripReader.tabsOf(window)
        val own = tabBar.splitBarOf(window)?.adapter
        val action = {
            info.isHidden = true
            // the platform keeps the editor component of the selected tab attached to the strip; detached, the content area is empty
            if (tabs.isEmptyVisible) info.component?.let { c -> if (c.parent === tabs) tabs.remove(c) }
        }
        if (own != null) own.ownChange(action) else action()
    }

    /**
     * Brings every placeholder in line (called by [TabBar.sync], i.e. after every file opened / closed / selection change): hides the
     * ones that are not hidden (except those of [unsettled] windows), remembers the windows that hold one, and queues a look at each
     * ([recheck]). Re-entrant calls (hiding changes the selection, which syncs again) do nothing.
     */
    fun settle() {
        if (settling || tabBar.host == null || project.isDisposed) return
        settling = true
        try {
            for (window in FileEditorManagerEx.getInstanceEx(project).windows) {
                if (window.isDisposed) continue
                val info = placeholderIn(window)
                if (info == null) {
                    tracked.remove(window)
                    continue
                }
                (info.`object` as? VirtualFile)?.let { owners[it] = window }
                tracked.add(window)
                if (window !in unsettled) hide(window, info)
                schedule(window)
            }
        }
        finally {
            settling = false
        }
    }

    // ---- keep-alive and the user closing a temporary split ---------------------------------------------------------------

    /**
     * [file] is about to be closed somewhere (`FileEditorManagerListener.Before.beforeFileClosed`, inside `EditorWindow.closeFile`).
     * If it is a placeholder that we are not closing ourselves, the user is closing a temporary split ([closing]); otherwise this is
     * keep-alive: in every split where it is the last real tab, that holds an empty group (or a group of which this is the only tab)
     * and that would close, a placeholder is opened first.
     */
    fun beforeFileClosed(file: VirtualFile) {
        if (project.isDisposed || tabBar.host == null) return
        if (isPlaceholder(file)) {
            if (quiet > 0) return
            val window = tabBar.windows().firstOrNull { !it.isDisposed && it.getComposite(file) != null } ?: return
            if (!wouldClose(window)) return // the main area's last window stays open: its pills stay, nothing to move
            tabBar.splitBarOf(window)?.let { closing(window, it) }
            return
        }
        for (bar in tabBar.bars()) {
            val window = bar.split.window
            if (window.isDisposed || window.getComposite(file) == null || placeholderIn(window) != null) continue
            if (StripReader.allFiles(StripReader.tabsOf(window)) != listOf(file)) continue
            if (!wouldClose(window)) continue
            // an empty group, or a group whose only tab this is (it is about to become empty and the user is asked whether to keep it,
            // the split must still be there to keep it in; a Delete answer then ends the temporary split)
            if (bar.groups.none { g -> g.members == listOf(file) || g.members.none { window.getComposite(it) != null } }) continue
            LOG.info("keep-alive: the split's last tab ${file.name} is closing while it holds an empty group")
            // the placeholder must stay visible until the close is over (a detached window closes when no tab is visible) and opening
            // it lets the event queue run: a picture of the split as it is now hides those frames, so the user sees the split go from
            // its last tab straight to its pills (no one-frame "(empty groups)" tab)
            val freeze = freezeSplit(window)
            // marked first: opening the file lets the event queue run (`blockingWaitForCompositeFileOpen`), and a look at this split
            // would find its closing tab still there and take the placeholder for a leftover
            unsettled += window
            if (ensure(window) == null) {
                unsettled -= window
                freeze?.release()
                continue
            }
            ApplicationManager.getApplication().invokeLater({
                unsettled.remove(window)
                try {
                    if (!project.isDisposed) tabBar.sync()
                }
                finally {
                    freeze?.release()
                }
            }, ModalityState.any())
        }
    }

    /**
     * A still picture of [window]'s whole tab component (strip and editor area), shown over it until released ([StripFreeze]); null if
     * it is not showing. Held from just before a keep-alive placeholder opens until it is hidden.
     */
    private fun freezeSplit(window: EditorWindow): StripFreeze? {
        val component = StripReader.tabsOf(window).component
        return StripFreeze.over(component, Rectangle(0, 0, component.width, component.height))
    }

    /** The split of [window] is going away with its placeholder; the host moves or deletes its empty groups, once. */
    private fun closing(window: EditorWindow, bar: SplitBar) {
        if (project.isDisposed || !project.isOpen) return // project close never asks or moves
        if (!handled.add(window)) return
        val groups = bar.groups
        LOG.info("temporary split closing with ${groups.size} group(s)")
        if (groups.isNotEmpty()) tabBar.host?.temporarySplitClosing(bar.split, groups)
    }

    /**
     * The split of [window] has been disposed ([TabBar.sync] noticed it). If it held a placeholder and the host has not been told, this
     * is a close that gave no warning (an unsplit merged it away, its detached window was closed): the host hears of it now, with the
     * groups last applied there.
     */
    fun windowGone(window: EditorWindow, bar: SplitBar) {
        owners.values.removeAll { it === window }
        if (!tracked.remove(window)) return
        unsettled.remove(window)
        closing(window, bar)
    }

    /** A file was closed: a placeholder closed by anything but us leaves a Reopen Closed Tab entry that would reopen it; it is removed. */
    fun fileClosed(file: VirtualFile) {
        if (!isPlaceholder(file)) return
        val window = owners.remove(file) ?: return
        TabClose.dropEntryOf(window, file)
    }

    // ---- becoming normal, ending ---------------------------------------------------------------------------------------------

    /** Queues a look at [window]'s placeholder for the end of the current event. */
    fun schedule(window: EditorWindow) {
        queued += window
        if (queueScheduled) return
        queueScheduled = true
        ApplicationManager.getApplication().invokeLater({
            queueScheduled = false
            val windows = queued.toList()
            queued.clear()
            for (w in windows) recheck(w)
        }, ModalityState.any(), project.disposed)
    }

    /**
     * Becomes normal: a real tab has arrived in [window]. Ends: the host lists no group for it any more. Either way the placeholder is
     * closed quietly and the platform closes the split if it is no longer needed.
     */
    private fun recheck(window: EditorWindow) {
        if (window.isDisposed || project.isDisposed || window in unsettled) return
        val host = tabBar.host ?: return
        val bar = tabBar.splitBarOf(window) ?: return
        val placeholder = placeholderIn(window)?.`object` as? PlaceholderFile ?: return
        val hasRealTab = StripReader.allFiles(StripReader.tabsOf(window)).isNotEmpty()
        if (hasRealTab || (!placeholder.fresh && host.groupsIn(bar.split).isEmpty())) {
            LOG.info(if (hasRealTab) "temporary split becomes a normal split" else "temporary split ends: no group left")
            closeQuietly(window)
        }
    }

    /** Closes [window]'s placeholder as our own change: no Reopen Closed Tab entry, no hand-over to the host. */
    fun closeQuietly(window: EditorWindow) {
        val info = placeholderIn(window) ?: return
        val file = info.`object` as? VirtualFile ?: return
        tracked.remove(window)
        unsettled.remove(window)
        owners.remove(file)
        quiet++
        try {
            TabClose.closeForMove(window, file, disposeIfNeeded = true)
        }
        finally {
            quiet--
        }
    }

    /** Closes every placeholder quietly (the project is closing and its layout is about to be saved, or the host is detached). */
    fun closeAll() {
        for (window in FileEditorManagerEx.getInstanceEx(project).windows.toList()) {
            if (!window.isDisposed && placeholderIn(window) != null) closeQuietly(window)
        }
    }
}
