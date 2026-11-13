package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit

private val LOG = logger<TabBarDnd>()

/**
 * Moves a whole group instance from this split to [target] (INT-6, SPLIT-5), merging with the target's instance of the same
 * group if it has one (the merged tabs go where the existing instance is; otherwise at [boundary] of [targetUnits]).
 *
 * The platform has no "move editor to another split" API that keeps the tab: its own drag and "Move to opposite group" close the
 * file in the source and open it again in the target with `CLOSING_TO_REOPEN` set on the file, which tells editors to keep their
 * state (`MoveEditorToOppositeTabGroupAction`, `EditorTabbedContainerDragOutDelegate.dragOutFinished`). We do the same per file.
 * That call is `@ApiStatus.Internal` (`FileEditorManagerEx.openFile(file, window, FileEditorOpenOptions)`); caret/scroll restore is
 * expected to work like the platform's own tab drag but is unverified. The Reopen Closed Tab entry that each close adds to the source split is removed again
 * (`TabClose.closeForMove`). The per-file move is [transferFile], shared with the tab drag ([moveTabToSplit]).
 *
 * A group that was dragged as its collapsed header (INT-6) comes back as it was: [expanding] is its id when it was expanded, and the
 * arrival plays the expand animation in the target (nothing else animates, COLL-7); [selectedBefore] is the tab that was selected when the
 * drag began (the drag moved the selection off the group), selected in the target again if it was one of the group's.
 *
 * Order of work: leave the source model first (so no header/hidden tab refers to files that are about to be closed), open the
 * files in the target, re-order the target in one go, then hand the merged group to the target model.
 */
internal fun TabBarDnd.moveGroupToSplit(
    unit: MoveUnit<VirtualFile>,
    target: TabBarDnd,
    targetUnits: List<MoveUnit<VirtualFile>>,
    boundary: Int,
    freezes: List<StripFreeze> = freezeStrips(target),
    selectedBefore: VirtualFile? = adapter.selectedFile(),
    expanding: String? = null,
) {
    runMove(target, freezes) { moveNow(unit, target, targetUnits, boundary, selectedBefore, expanding) }
}

/**
 * Pictures of this strip and [target]'s, shown over them until [releaseLater] ([StripFreeze]). The platform lets the event queue run
 * while it closes and opens the editors, so both strips would otherwise be painted half-way through the move. A drop takes them
 * *before* it puts the source strip back and closes the target's drop gap, so the user sees the strips as they were at the release
 * and then the result, nothing in between.
 */
internal fun TabBarDnd.freezeStrips(target: TabBarDnd): List<StripFreeze> = listOfNotNull(freeze(), target.freeze())

/** Removes [freezes] once the events the move left in the queue (late `fileOpened`, layouts) have run. */
internal fun releaseLater(freezes: List<StripFreeze>) {
    if (freezes.isEmpty()) return
    ApplicationManager.getApplication().invokeLater({ freezes.forEach { it.release() } }, ModalityState.any())
}

/** A [StripFreeze] over this split's tab rows (null if the strip is not showing). */
private fun TabBarDnd.freeze(): StripFreeze? {
    val screen = stripScreenRect() ?: return null
    val origin = origin() ?: return null
    screen.translate(-origin.x, -origin.y)
    return StripFreeze.over(tabs.component, screen)
}

private fun TabBarDnd.moveNow(
    unit: MoveUnit<VirtualFile>,
    target: TabBarDnd,
    targetUnits: List<MoveUnit<VirtualFile>>,
    boundary: Int,
    selectedBefore: VirtualFile?,
    expanding: String?,
) {
    val groupId = unit.groupId ?: return
    val files = unit.keys
    if (files.isEmpty()) return
    val sourceGroups = model.groups()
    val group = sourceGroups.firstOrNull { it.id == groupId } ?: return
    val selected = selectedBefore
    val targetWindow = target.adapter.window

    // Where do the files go in the target?
    val existingUnit = targetUnits.firstOrNull { it.groupId == groupId }
    val merged: List<VirtualFile>
    val targetOrder: List<VirtualFile>
    if (existingUnit != null) {
        merged = existingUnit.keys + files.filter { it !in existingUnit.keys }
        targetOrder = targetUnits.flatMap { if (it === existingUnit) merged else it.keys }
    }
    else {
        merged = files
        targetOrder = targetUnits.take(boundary).flatMap { it.keys } + files + targetUnits.drop(boundary).flatMap { it.keys }
    }
    val targetGroups = target.model.groups()

    // 1. source leaves: the group disappears from the source model (this also shows its hidden tabs).
    model.commit(sourceGroups.filter { it.id != groupId })
    // 2. target: nothing may be hidden while files are added (index spaces of the platform mix hidden and visible tabs).
    if (targetGroups.any { it.collapsed }) target.adapter.setGroups(targetGroups.map { it.copy(collapsed = false) })

    for ((i, file) in files.withIndex()) {
        transferFile(file, target, close = true, keepHistory = false, disposeSource = i == files.lastIndex)
    }

    val open = target.tabs.tabs.mapNotNull { it.`object` as? VirtualFile }
    val order = (targetOrder.filter { it in open }.distinct() + open.filter { it !in targetOrder })
    target.adapter.applyOrder(order)
    val mergedOpen = order.filter { it in merged }
    target.model.acceptGroup(
        (targetGroups.firstOrNull { it.id == groupId } ?: group).copy(members = mergedOpen, collapsed = group.collapsed), expanding,
    )
    if (selected != null && selected in files && !group.collapsed) targetWindow.setSelectedComposite(selected, true)
    LOG.info("group $groupId moved: ${files.size} files to another split, merged=${existingUnit != null}")
}
