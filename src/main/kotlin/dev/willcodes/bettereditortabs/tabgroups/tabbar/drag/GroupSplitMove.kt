package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ScreenUtil
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorSide
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

private val LOG = logger<TabBarDnd>()

/*
 * A group dropped on the edge of an editor area, or outside every editor window (INT-6, SPLIT-5, SCOPE-6): the group's whole instance
 * moves into a split that does not exist yet. The move itself is moveGroupToSplit's (moveNow: the source model lets go, the files
 * go over with transferFile and no Reopen Closed Tab history, the target is ordered once and given the group as one instance, the
 * expand animation plays if it was expanded, the previously selected tab is selected there if it was one of the group's); this file
 * only creates the target first.
 *
 * Creation works like the platform's drop of a tab does: the first file of the group makes the split (EditorWindow.split, which opens
 * the file in the new split and keeps it open in the source) or the window (FileEditorManagerImpl.openFileInNewWindow, which also
 * keeps the source tab), and the files that follow, with the first one's source tab, are moved by moveNow as for any other split. The
 * split is created before anything leaves the source, so a source that the move empties disposes itself last (a split goes away with
 * its last tab, as in the platform). The new split's drag layer exists once TabBar.sync has run; the move goes through runMove so
 * that SPLIT-2's placement does not take the arrival for a file the user opened there.
 */

/**
 * Makes a new split next to [anchor] (any split of the project, also this one) on [side] of it and moves the group [unit] into it. The
 * side and the new split's place are the platform's (`DockableEditorTabbedContainer.add`: TOP and BOTTOM split vertically, LEFT and
 * RIGHT horizontally, the new split is first for TOP and LEFT). `EditorWindow.split` is called with every argument given, never through
 * its Kotlin default arguments (`$default` bridges change between platform releases). False (nothing moved) if the split could not be made.
 */
internal fun TabBarDnd.moveGroupToNewSplit(
    unit: MoveUnit<VirtualFile>,
    anchor: TabBarDnd,
    side: EditorSide,
    freezes: List<StripFreeze>,
    selectedBefore: VirtualFile?,
    expanding: String?,
    copy: Boolean = false,
): Boolean {
    if (side == EditorSide.CENTER) return false
    val first = unit.keys.firstOrNull() ?: return false
    val project = adapter.window.manager.project
    val orientation = if (side.splitsVertically) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
    val created: EditorWindow? = adapter.ownChange {
        anchor.adapter.ownChange {
            anchor.adapter.window.split(orientation, true, first, true, !side.newSplitFirst, false)?.also { project.service<TabBar>().sync() }
        }
    }
    if (created == null) {
        LOG.warn("no new split for the group's first file ${first.name}")
        return false
    }
    val target = peers().firstOrNull { it.adapter.window === created }
    if (target == null) {
        LOG.warn("the new split has no drag layer")
        return false
    }
    runMove(target, freezes) { moveNow(unit, target, emptyList(), 0, selectedBefore, expanding, copy) }
    LOG.info("group ${unit.groupId} ${if (copy) "copied" else "moved"} to a new split: side=$side, ${unit.keys.size} files")
    return true
}

/**
 * Makes a detached editor window around [screen] and moves the group [unit] into it. The window is the platform's
 * (`FileEditorManagerImpl.openFileInNewWindow`, which opens the first file in a new dock window and keeps its source tab; public, and
 * both the 262 and the 263 jar have `(VirtualFile, boolean)`); it is then centred on the pointer and fitted to the screen as
 * `DockManagerImpl.createNewDockContainerFor` places a window made for a dragged tab (its size stays the platform's default for a
 * window: the file variant of that function does not size by the dragged content). False (nothing moved) if no window could be made.
 */
internal fun TabBarDnd.moveGroupToNewWindow(
    unit: MoveUnit<VirtualFile>,
    screen: Point,
    freezes: List<StripFreeze>,
    selectedBefore: VirtualFile?,
    expanding: String?,
    copy: Boolean = false,
): Boolean {
    val first = unit.keys.firstOrNull() ?: return false
    val project = adapter.window.manager.project
    val manager = FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl
    if (manager == null) {
        LOG.warn("no FileEditorManagerImpl: a group cannot be moved to a new window")
        return false
    }
    val before = manager.windows.toSet()
    adapter.ownChange {
        manager.openFileInNewWindow(first, false)
        project.service<TabBar>().sync()
    }
    val created = manager.windows.firstOrNull { it !in before && !it.isDisposed }
    val target = created?.let { w -> peers().firstOrNull { it.adapter.window === w } }
    if (target == null) {
        LOG.warn("no new window for the group's first file ${first.name}")
        return false
    }
    centreWindow(target, screen)
    runMove(target, freezes) { moveNow(unit, target, emptyList(), 0, selectedBefore, expanding, copy) }
    LOG.info("group ${unit.groupId} ${if (copy) "copied" else "moved"} to a new window: ${unit.keys.size} files")
    return true
}

/** Puts the window of [target] with its centre at [screen], fitted to the screen (the placement of `createNewDockContainerFor`). No-op without a window. */
private fun centreWindow(target: TabBarDnd, screen: Point) {
    val window = SwingUtilities.getWindowAncestor(target.tabs.component) ?: return
    val bounds = Rectangle(window.bounds)
    bounds.setLocation(screen.x - bounds.width / 2, screen.y - bounds.height / 2)
    ScreenUtil.moveRectangleToFitTheScreen(bounds)
    ScreenUtil.cropRectangleToFitTheScreen(bounds)
    window.bounds = bounds
}
