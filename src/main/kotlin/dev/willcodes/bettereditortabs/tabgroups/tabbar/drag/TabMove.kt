package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.CopyJoin
import dev.willcodes.bettereditortabs.tabgroups.model.groups.instanceStripOrder
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabClose
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader

private val LOG = logger<TabBarDnd>()

/**
 * Runs [block], a change that touches this split and [target] (a move of tabs between them), as the strips' own change: closing in the
 * source, opening and re-hiding in the target make the platform move both splits' selections, also onto tabs that are hidden for a
 * moment, and none of that is navigation (COLL-4). [freezes] ([freezeStrips]) are released once the events the move left in the queue
 * have run. Shared by [moveGroupToSplit] and [moveTabToSplit].
 */
internal fun TabBarDnd.runMove(target: TabBarDnd, freezes: List<StripFreeze>, block: () -> Unit) {
    try {
        adapter.ownChange { target.adapter.ownChange(block) }
        // SPLIT-2: what the move opened in the target is not a user's "open in another split" (membership came from the drop)
        target.acknowledgeOpened()
    }
    finally {
        releaseLater(freezes)
    }
}

/**
 * Moves the tab of [file] from this split to [target] the way the platform's own drag does: the platform has no "move editor to
 * another split" API that keeps the tab, its drag and "Move to opposite group" close the file in the source and open it again in the
 * target with `CLOSING_TO_REOPEN` set, which tells editors to keep their state (`EditorTabbedContainerDragOutDelegate.dragOutFinished`,
 * `DockableEditorTabbedContainer.add`, `MoveEditorToOppositeTabGroupAction`). `FileEditorManagerEx.openFile(file, window, options)`
 * is `@ApiStatus.Internal`.
 *
 * [close]: close the tab here (a move); false leaves it open (a copy). [keepHistory]: the close stays in the source split's Reopen
 * Closed Tab history (a tab drag: the platform's drag does that too, `EditorWindow.closeFile`); false removes the entry the close
 * made ([TabClose.closeForMove], a group move, INT-6). A file that is open in [target] already is not opened again.
 * [disposeSource]: close the split too if this was its last tab. The file is opened in the target in the background (neither
 * selected nor focused): the caller selects what it wants.
 */
internal fun TabBarDnd.transferFile(file: VirtualFile, target: TabBarDnd, close: Boolean, keepHistory: Boolean, disposeSource: Boolean) {
    val sourceWindow = adapter.window
    val manager = FileEditorManagerEx.getInstanceEx(sourceWindow.manager.project)
    file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true)
    try {
        val alreadyThere = target.tabs.findInfo(file) != null
        if (close) {
            if (keepHistory) sourceWindow.closeFile(file, disposeSource) else TabClose.closeForMove(sourceWindow, file, disposeSource)
        }
        if (!alreadyThere) manager.openFile(file, target.adapter.window, PlatformInternals.backgroundOpenOptions())
    }
    finally {
        file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)
    }
}

/**
 * Drops the tab of [file] (pinned: [pinned]) from this split into [target] at [landing] (DRAG-5): the slot and group a [TabDragSession]
 * on the target's strip chose, which count the target's cells without the file. A pinned tab stays pinned; it never joins a group
 * (DRAG-4: the landing says so already). [copy] is the platform's copy modifier (Ctrl): the tab stays open here.
 *
 * What the platform does for a tab dropped on another split and is mirrored here (`EditorTabbedContainerDragOutDelegate.dragOutFinished`,
 * `DockableEditorTabbedContainer.add`):
 * - the source tab is closed first (a Reopen Closed Tab entry stays in the source split; the source selects a neighbour; an emptied
 *   split goes away), then the file is opened in the target;
 * - the arrival is selected and focused in the target;
 * - a file that is already open in the target is not opened twice: that tab moves to the landing, the source tab closes.
 *
 * Order of work, as for a group ([moveGroupToSplit]): the source model lets go of the file first (so no group refers to a tab that is
 * about to close; an emptied group is removed by the model, MEM-5), collapsed groups of the target are expanded for a moment (index
 * spaces of the platform mix hidden and visible tabs), the file moves, the target is re-ordered in one go and the target model gets
 * the new membership (MEM-2 then sees the arrival as a member where it belongs and leaves it alone). Both splits' own selection churn
 * is not navigation, and both are shown as a picture until the events have run ([freezes], see [freezeStrips]). Nothing here animates a collapse
 * (`SplitGroups.commit` refreshes with `animate = false`).
 *
 * The target's drop gap must be closed ([TabDragSession.clearPreview]) before this runs, but after [freezes] were taken.
 */
internal fun TabBarDnd.moveTabToSplit(
    file: VirtualFile,
    pinned: Boolean,
    target: TabBarDnd,
    landing: Landing,
    copy: Boolean,
    freezes: List<StripFreeze> = freezeStrips(target),
) {
    runMove(target, freezes) { moveTabNow(file, pinned, target, landing, copy) }
}

private fun TabBarDnd.moveTabNow(file: VirtualFile, pinned: Boolean, target: TabBarDnd, landing: Landing, copy: Boolean) {
    val targetWindow = target.adapter.window
    val sourceGroups = model.groups()
    val targetGroups = target.model.groups()
    // the order the target gets: its strip as it is now, without the file, and the file at the landing
    val cells = TabDropPlanner.cellsOf(target.adapter.stripUnits(), file)
    val flat = TabDropPlanner.flatOrder(cells, landing, file, targetGroups)

    // 1. the source lets go of the file
    if (!copy && sourceGroups.any { file in it.members }) {
        model.commit(sourceGroups.map { g -> if (file in g.members) g.copy(members = g.members - file) else g })
    }
    // 2. nothing may be hidden in the target while a file is added
    if (targetGroups.any { it.collapsed }) target.adapter.setGroups(targetGroups.map { it.copy(collapsed = false) })
    // 3. the tab moves
    transferFile(file, target, close = !copy, keepHistory = true, disposeSource = true)
    if (targetWindow.getComposite(file) == null) {
        LOG.warn("tab ${file.name} did not arrive in the target split")
        target.model.commit(targetGroups)
        return
    }
    if (pinned && !targetWindow.isFilePinned(file)) targetWindow.setFilePinned(file, true)
    // 4. order and membership of the target
    val open = StripReader.allFiles(target.tabs)
    val order = flat.filter { it in open }.distinct() + open.filter { it !in flat }
    target.adapter.applyOrder(order)
    val copiedFrom = if (copy && !pinned) sourceGroups.firstOrNull { file in it.members } else null
    if (copiedFrom != null) target.model.commit(joinOwnGroup(target, targetGroups, copiedFrom, file, order))
    else target.model.commit(TabDropPlanner.groupsAfterDrop(targetGroups, file, TabDropPlanner.Applied(landing, order)))
    // 5. like the platform's drop: the arrival is the selected, focused tab of the target
    targetWindow.setSelectedComposite(file, true)
    LOG.info("tab ${file.name} ${if (copy) "copied" else "moved"} to another split: group ${copiedFrom?.id ?: landing.groupId ?: "none"}, slot ${landing.slot}")
}

/**
 * SPLIT-2 for a copy: a grouped tab copied into [target] (Ctrl) joins its own group [copiedFrom] there, wherever it was dropped
 * ([CopyJoin]: behind the group's last tab, or a new instance at the drop place, never inside another group). The target's tabs are
 * re-ordered to match; the group is shown expanded so the arrival is visible. Returns the target's new groups, in strip order.
 */
private fun joinOwnGroup(
    target: TabBarDnd,
    targetGroups: List<GroupDecoration>,
    copiedFrom: GroupDecoration,
    file: VirtualFile,
    order: List<VirtualFile>,
): List<GroupDecoration> {
    val placed = CopyJoin.place(order, file, targetGroups.map { it.id to it.members }, copiedFrom.id)
    if (placed.order != order) target.adapter.applyOrder(placed.order)
    val all = if (targetGroups.any { it.id == copiedFrom.id }) targetGroups else targetGroups + copiedFrom
    val groups = all.map { g ->
        g.copy(members = placed.members[g.id].orEmpty(), collapsed = if (g.id == copiedFrom.id) false else g.collapsed)
    }
    val ids = instanceStripOrder(groups.map { it.id to it.members }, placed.order)
    return ids.mapNotNull { id -> groups.firstOrNull { it.id == id } }
}
