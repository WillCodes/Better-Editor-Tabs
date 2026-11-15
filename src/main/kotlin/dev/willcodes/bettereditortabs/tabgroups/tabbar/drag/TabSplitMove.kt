package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorSide
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import javax.swing.JSplitPane

private val LOG = logger<TabBarDnd>()

/*
 * A tab dropped on the edge of the editor area of a split whose strip shows no tab but the dragged one (a "bare" split, [dev.willcodes.bettereditortabs.tabgroups.model.strip.TabEditorDrop]) makes a new split with the tab in it, as the platform's
 * own drop does (`DockableEditorTabbedContainer.add`: `EditorWindow.split` opens the file in the new split and keeps it open in the
 * source, then `EditorTabbedContainerDragOutDelegate.dragOutFinished` closes the source tab unless Ctrl is held). The creation is the
 * group drag's ([moveGroupToNewSplit]); the move that follows is [moveTabToSplit] (the source tab is closed with a Reopen Closed Tab
 * entry, a copy keeps it, a pinned tab stays pinned).
 */

/** The split a tab drag made ([createSplitFor]) and its drag layer. */
internal class NewSplit(val window: EditorWindow, val dnd: TabBarDnd)

/**
 * Makes a new split next to [anchor] (any split of the project, also this one) on [side] of it with [file] opened in it, as the platform
 * does (TOP and BOTTOM split vertically, LEFT and RIGHT horizontally, the new split is first for TOP and LEFT). `EditorWindow.split` is
 * called with every argument given, never through its Kotlin default arguments (`$default` bridges change between platform releases);
 * the new split becomes the current one (`focusNew`). Null (nothing changed) if the split could not be made.
 */
internal fun TabBarDnd.createSplitFor(file: VirtualFile, anchor: TabBarDnd, side: EditorSide): NewSplit? {
    if (side == EditorSide.CENTER) return null
    val project = adapter.window.manager.project
    val orientation = if (side.splitsVertically) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
    val created: EditorWindow? = adapter.ownChange {
        anchor.adapter.ownChange {
            anchor.adapter.window.split(orientation, true, file, true, !side.newSplitFirst, false)?.also { project.service<TabBar>().sync() }
        }
    }
    if (created == null) {
        LOG.warn("no new split for the tab ${file.name}")
        return null
    }
    val target = peers().firstOrNull { it.adapter.window === created }
    if (target == null) {
        LOG.warn("the new split has no drag layer")
        project.service<TabBar>().temporary.created(created)
        return null
    }
    return NewSplit(created, target)
}

/**
 * Moves the tab of [file] into the split [created] that [createSplitFor] made ([copy]: the source tab stays). The file is open there
 * already; the rest is [moveTabToSplit]. The split may end like any temporary split afterwards ([dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.TemporarySplits.created]).
 */
internal fun TabBarDnd.moveTabToCreatedSplit(file: VirtualFile, pinned: Boolean, created: NewSplit, copy: Boolean, freezes: List<StripFreeze>) {
    val project = adapter.window.manager.project
    try {
        moveTabToSplit(file, pinned, created.dnd, Landing(0, null, null), copy, freezes)
    }
    finally {
        project.service<TabBar>().temporary.created(created.window)
    }
    LOG.info("tab ${file.name} ${if (copy) "copied" else "moved"} to a new split")
}
