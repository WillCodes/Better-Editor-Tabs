package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.EditorsSplitters
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ComponentUtil
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ClosedMark
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader

private val LOG = logger<GroupReopen>()

/**
 * What bringing a group back needs from the platform.
 *
 * Internal APIs wrapped, and why:
 * - `EditorWindow.closeFile` through [TabClose.closeForMove]: closes the tabs of a group without leaving Reopen Closed Tab entries
 *   (the stash and the closed-group records are how the group comes back), and the history's top ([PlatformInternals.removedTabs]) is
 *   how a record knows whether it is still the most recent close of its split ([isMostRecent]).
 * - `FileEditorManagerEx.openFile(file, window, FileEditorOpenOptions)` (`@ApiStatus.Internal`): opens a file in a given split in the
 *   background, as the group moves do ([openInOrder]).
 * - `EditorsSplitters.currentWindow` and `PlatformCoreDataKeys.CONTEXT_COMPONENT`: the split the built-in Reopen Closed Tab acts on
 *   ([reopenWindow]).
 */
internal object GroupReopen {
    /**
     * Closes [files] (those open in [window]) without Reopen Closed Tab entries and returns a mark of that close. If the history cannot
     * be read (platform change) the entries stay (only cosmetic) and the mark is [ClosedMark.historyReadable] false, so
     * [isMostRecent] answers true (fail soft).
     */
    fun closeQuietly(window: EditorWindow, files: List<VirtualFile>): ClosedMark {
        for (file in files) {
            if (window.isDisposed) break
            if (window.getComposite(file) != null) TabClose.closeForMove(window, file, disposeIfNeeded = true)
        }
        val history = PlatformInternals.removedTabs(window)
        return ClosedMark(window, history?.lastOrNull(), history != null)
    }

    /**
     * True if nothing was closed or reopened in the mark's split since the close: the split's history top is still the entry that was
     * on top then (the same object; both empty counts too). An unreadable history counts as "still the most recent". A split that is
     * gone is not.
     */
    fun isMostRecent(mark: ClosedMark): Boolean {
        if (mark.window.isDisposed) return false
        if (!mark.historyReadable) return true
        val history = PlatformInternals.removedTabs(mark.window) ?: return true
        return history.lastOrNull() === mark.historyTop
    }

    /**
     * The split the built-in Reopen Closed Tab acts on: the current window of the editor splitters around the action's context
     * component, else the project's current window.
     */
    fun reopenWindow(project: Project, e: AnActionEvent): EditorWindow? {
        val component = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        val context = component?.let { ComponentUtil.getParentOfType(EditorsSplitters::class.java, it)?.currentWindow }
        return (context ?: FileEditorManagerEx.getInstanceEx(project).currentWindow)?.takeIf { !it.isDisposed }
    }

    /**
     * Opens [toOpen] in [bar]'s split, in the background (neither selected nor focused), and puts the split's tabs in [order] (the whole
     * logical order, files not open are ignored; open tabs it omits follow). Pinned tabs are the caller's business (they are in
     * [order] first). Collapsed groups are shown for the moment, without animation: the platform's index spaces mix hidden and visible
     * tabs, as in a group move; the caller refreshes the split afterwards, which hides them again (no flip, so nothing animates). It
     * runs as the strip's own change (the selection churn is not a user selection of a hidden tab) and the opened files are acknowledged (the linked-file rule and the
     * new-tab placement do not act on them: the caller gave them their membership). Returns the files of [toOpen] that have a tab now.
     */
    fun openInOrder(bar: SplitBar, toOpen: List<VirtualFile>, order: List<VirtualFile>): List<VirtualFile> {
        val window = bar.split.window
        val adapter = bar.adapter
        val manager = FileEditorManagerEx.getInstanceEx(window.manager.project)
        adapter.ownChange {
            val groups = bar.groups
            if (groups.any { it.collapsed }) adapter.setGroups(groups.map { it.copy(collapsed = false) })
            for (file in toOpen) {
                if (window.isDisposed) break
                if (window.getComposite(file) == null) manager.openFile(file, window, PlatformInternals.backgroundOpenOptions())
            }
            if (!window.isDisposed) {
                val open = StripReader.allFiles(bar.dnd.tabs)
                adapter.applyOrder(order.filter { it in open }.distinct() + open.filter { it !in order })
            }
        }
        bar.dnd.acknowledgeOpened()
        val opened = toOpen.filter { !window.isDisposed && window.getComposite(it) != null }
        if (opened.size != toOpen.size) LOG.warn("${toOpen.size - opened.size} file(s) did not open in the split while a group was brought back")
        return opened
    }
}
